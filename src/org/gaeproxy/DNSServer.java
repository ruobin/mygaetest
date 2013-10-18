package org.gaeproxy;

import android.content.Context;
import android.util.Log;
import android.util.Pair;
import com.google.analytics.tracking.android.EasyTracker;
import com.j256.ormlite.android.apptools.OpenHelperManager;
import com.j256.ormlite.dao.Dao;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import org.apache.commons.codec.binary.Base64;
import org.gaeproxy.db.DNSResponse;
import org.gaeproxy.db.DatabaseHelper;
import org.gaeproxy.db.DomainValidator;

/**
 * DNS Proxy Local Server
 *
 * @author biaji
 */
public class DNSServer implements Runnable {

  public static byte[] int2byte(int res) {
    byte[] targets = new byte[4];

    targets[0] = (byte) (res & 0xff);
    targets[1] = (byte) ((res >> 8) & 0xff);
    targets[2] = (byte) ((res >> 16) & 0xff);
    targets[3] = (byte) (res >>> 24);
    return targets;
  }

  private final String TAG = "GAEDNSProxy";

  private DatagramSocket datagramSocket;
  private ServerSocket serverSocket;

  public final HashSet<String> domains;

  private int srvPort = 8153;
  final protected int DNS_PKG_HEADER_LEN = 12;
  final private int[] DNS_HEADERS = { 0, 0, 0x81, 0x80, 0, 0, 0, 0, 0, 0, 0, 0 };
  final private int[] DNS_PAYLOAD = {
      0xc0, 0x0c, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x3c, 0x00, 0x04
  };

  /** Hosts */
  private Hashtable<String, String> orgCache = new Hashtable<String, String>();

  private String appHost = "203.208.46.1";

  private static final String CANT_RESOLVE = "Error";

  private DatabaseHelper helper;

  private final static AsyncHttpClient client = new AsyncHttpClient();

  public DNSServer(Context ctx, String appHost, Pair<String, String> orgHost) {

    this.appHost = appHost;

    if (orgHost != null) this.orgCache.put(orgHost.first, orgHost.second);

    client.setTimeout(6 * 1000);

    domains = new HashSet<String>();

    OpenHelperManager.setOpenHelperClass(DatabaseHelper.class);

    if (helper == null) {
      helper = OpenHelperManager.getHelper(ctx, DatabaseHelper.class);
    }

    try {
      datagramSocket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
      srvPort = datagramSocket.getLocalPort();
      Log.d(TAG, "start at port " + srvPort);
    } catch (SocketException e) {
      Log.e(TAG, "error to initilized at port " + srvPort, e);
    } catch (UnknownHostException e) {
      Log.e(TAG, "error to initilized at port " + srvPort, e);
    }
  }

  /** Add resolve result to cache */
  private synchronized void addToCache(String questDomainName, byte[] answer) {
    DNSResponse response = new DNSResponse(questDomainName);
    response.setAddress(DNSResponse.getIPString(answer));
    try {
      Dao<DNSResponse, String> dnsCacheDao = helper.getDNSCacheDao();
      dnsCacheDao.createOrUpdate(response);
    } catch (Exception e) {
      Log.e(TAG, "Cannot open DAO", e);
    }
  }

  private synchronized void updateCache(DNSResponse response) {
    try {
      Dao<DNSResponse, String> dnsCacheDao = helper.getDNSCacheDao();
      dnsCacheDao.createOrUpdate(response);
    } catch (Exception e) {
      Log.e(TAG, "Cannot open DAO", e);
    }
  }

  public void close() throws IOException {
    serverSocket.close();
    datagramSocket.close();
    if (helper != null) {
      OpenHelperManager.releaseHelper();
      helper = null;
    }
    Log.i(TAG, "DNS Proxy closed");
  }

  /*
    * Create a DNS response packet, which will send back to application.
    *
    * @author yanghong
    *
    * Reference to:
    *
    * Mini Fake DNS server (Python)
    * http://code.activestate.com/recipes/491264-mini-fake-dns-server/
    *
    * DOMAIN NAMES - IMPLEMENTATION AND SPECIFICATION
    * http://www.ietf.org/rfc/rfc1035.txt
    */
  protected byte[] createDNSResponse(byte[] quest, byte[] ips) {
    int start = 0;
    byte[] response = new byte[4096];

    for (int val : DNS_HEADERS) {
      response[start] = (byte) val;
      start++;
    }

    System.arraycopy(quest, 0, response, 0, 2); /* 0:2 | NAME */
    System.arraycopy(quest, 4, response, 4, 2); /* 4:6 -> 4:6 | TYPE */
    System.arraycopy(quest, 4, response, 6, 2); /* 4:6 -> 7:9 | CLASS */
    /* 10:14 | TTL */
    System.arraycopy(quest, DNS_PKG_HEADER_LEN, response, start, quest.length - DNS_PKG_HEADER_LEN); /* 12:~ -> 15:~ */
    start += quest.length - DNS_PKG_HEADER_LEN;

    for (int val : DNS_PAYLOAD) {
      response[start] = (byte) val;
      start++;
    }

    /* IP address in response */
    for (byte ip : ips) {
      response[start] = ip;
      start++;
    }

    byte[] result = new byte[start];
    System.arraycopy(response, 0, result, 0, start);

    return result;
  }

  public void fetchAnswerHTTP(final DatagramPacket dnsq, final byte[] quest) {

    final String domain = getRequestDomain(quest);

    DomainValidator dv = DomainValidator.getInstance();
    /* Not support reverse domain name query */
    if (domain.endsWith("ip6.arpa") || domain.endsWith("in-addr.arpa") || !dv.isValid(domain)) {
      final byte[] answer = createDNSResponse(quest, parseIPString("127.0.0.1"));
      addToCache(domain, answer);
      sendDns(answer, dnsq, datagramSocket);
      synchronized (domains) {
        domains.remove(domain);
      }
      return;
    }

    final long startTime = System.currentTimeMillis();

    AsyncHttpResponseHandler handler = new AsyncHttpResponseHandler() {

      @Override
      public void onFinish() {
        synchronized (domains) {
          domains.remove(domain);
        }
      }

      @Override
      public void onSuccess(String response) {
        try {

          byte[] answer = null;

          if (response == null) {
            Log.e(TAG, "Failed to resolve domain name: " + domain);
            return;
          }

          response = response.trim();

          if (response.equals(CANT_RESOLVE)) {
            Log.e(TAG, "Cannot resolve domain name: " + domain);
            return;
          }

          byte[] ips = parseIPString(response);
          if (ips != null) {
            answer = createDNSResponse(quest, ips);
          }

          if (answer != null && answer.length != 0) {
            addToCache(domain, answer);
            sendDns(answer, dnsq, datagramSocket);
            Log.d(TAG, "Success to resolve: "
                + domain
                + " quest_length: "
                + quest.length
                + " answer_length: "
                + answer.length
                + " cost: "
                + (System.currentTimeMillis() - startTime) / 1000
                + "s "
                +
                "ip: "
                + DNSResponse.getIPString(answer));
          } else {
            Log.e(TAG, "The size of DNS packet returned is 0");
          }
        } catch (Exception e) {
          // Nothing
        }
      }
    };

    resolveDomainName(domain, handler);
  }

  /** Resolve UDP DNS request */
  protected String getRequestDomain(byte[] request) {
    String requestDomain = "";
    int reqLength = request.length;
    if (reqLength > 13) {
      byte[] question = new byte[reqLength - 12];
      System.arraycopy(request, 12, question, 0, reqLength - 12);
      requestDomain = parseDomain(question);
      if (requestDomain.length() > 1) {
        requestDomain = requestDomain.substring(0, requestDomain.length() - 1);
      }
    }
    return requestDomain;
  }

  public int getServPort() {
    return this.srvPort;
  }

  public boolean isClosed() {
    return datagramSocket.isClosed();
  }

  /** Load cache from DB */
  private void loadCache() {
    try {
      Dao<DNSResponse, String> dnsCacheDao = helper.getDNSCacheDao();
      List<DNSResponse> list = dnsCacheDao.queryForAll();
      for (DNSResponse resp : list) {
        // expire after 10 days
        if ((System.currentTimeMillis() - resp.getTimestamp()) > 864000000L) {
          Log.d(TAG, "deleted: " + resp.getRequest());
          dnsCacheDao.delete(resp);
        }
      }
    } catch (Exception e) {
      Log.e(TAG, "Cannot open DAO", e);
    }
  }

  /** Parse request to domain name */
  private String parseDomain(byte[] request) {

    String result = "";
    int length = request.length;
    int partLength = request[0];
    if (partLength == 0) return result;
    try {
      byte[] left = new byte[length - partLength - 1];
      System.arraycopy(request, partLength + 1, left, 0, length - partLength - 1);
      result = new String(request, 1, partLength) + ".";
      result += parseDomain(left);
    } catch (Exception e) {
      Log.e(TAG, e.getLocalizedMessage());
    }
    return result;
  }

  /** Parse IP string into byte, do validation. */
  protected byte[] parseIPString(String ip) {
    byte[] result = null;
    int value;
    int i = 0;
    String[] ips = null;

    ips = ip.split("\\.");

    // Log.d(TAG, "Start parse ip string: " + ip + ", Sectons: " +
    // ips.length);

    int IP_SECTION_LEN = 4;
    if (ips.length != IP_SECTION_LEN) {
      Log.e(TAG, "Malformed IP string : " + ip);
      return null;
    }

    result = new byte[IP_SECTION_LEN];

    for (String section : ips) {
      try {
        value = Integer.parseInt(section);

        /* 0.*.*.* and *.*.*.0 is invalid */
        if ((i == 0 || i == 3) && value == 0) {
          return null;
        }

        result[i] = (byte) value;
        i++;
      } catch (NumberFormatException e) {
        Log.e(TAG, "Malformed IP string: " + ip);
        return null;
      }
    }

    return result;
  }

  private synchronized DNSResponse queryFromCache(String questDomainName) {
    try {
      Dao<DNSResponse, String> dnsCacheDao = helper.getDNSCacheDao();
      return dnsCacheDao.queryForId(questDomainName);
    } catch (Exception e) {
      Log.e(TAG, "Cannot open DAO", e);
    }
    return null;
  }

  private synchronized String reverseQueryFromCache(String address) {
    try {
      Dao<DNSResponse, String> dnsCacheDao = helper.getDNSCacheDao();
      List<DNSResponse> list = dnsCacheDao.queryForEq("address", address);
      if (list == null || list.isEmpty()) return null;
      Collections.sort(list);
      return list.get(list.size() - 1).getRequest();
    } catch (Exception e) {
      Log.e(TAG, "Cannot open DAO", e);
    }
    return null;
  }

  /*
    * Resolve host name by access a DNSRelay running on GAE:
    *
    * Example:
    *
    * http://www.hosts.dotcloud.com/lookup.php?(domain name encoded)
    * http://gaednsproxy.appspot.com/?d=(domain name encoded)
    */
  private void resolveDomainName(String domain, AsyncHttpResponseHandler handler) {

    String encode_temp = new String(Base64.encodeBase64(domain.getBytes(), false));
    // Log.d(TAG, "BASE 64 pass 1: " + encode_temp);
    String encode_domain = new String(Base64.encodeBase64(encode_temp.getBytes(), false));
    // Log.d(TAG, "BASE 64 pass 2: " + encode_domain);

    String url = "http://myhosts.sinaapp.com/lookup.php?host=" + encode_domain;
    String host = "myhosts.sinaapp.com";
    url = url.replace(host, appHost);

    client.get(url, host, handler);
  }

  @Override
  public void run() {

    loadCache();

    new Thread(new ReverseDNSServer()).start();

    while (!datagramSocket.isClosed()) {
      try {
        byte[] qbuffer = new byte[1024];
        final DatagramPacket dnsq = new DatagramPacket(qbuffer, qbuffer.length);

        datagramSocket.receive(dnsq);

        // try to build dnsreq here
        byte[] data = dnsq.getData();
        int dnsqLength = dnsq.getLength();
        final byte[] udpreq = new byte[dnsqLength];
        System.arraycopy(data, 0, udpreq, 0, dnsqLength);

        // begin to query from dns cache
        final String questDomain = getRequestDomain(udpreq);
        DNSResponse resp = queryFromCache(questDomain);
        if (orgCache.containsKey(questDomain)) {
          byte[] ips = parseIPString(orgCache.get(questDomain));
          byte[] answer = createDNSResponse(udpreq, ips);
          addToCache(questDomain, answer);
          sendDns(answer, dnsq, datagramSocket);
          Log.d(TAG, "Custom DNS resolver: " + questDomain);
        } else if (resp != null) {
          String addr = resp.getAddress();
          updateCache(resp);
          sendDns(createDNSResponse(udpreq, parseIPString(addr)), dnsq, datagramSocket);
          Log.d(TAG, "DNS cache hit: " + questDomain);
          EasyTracker.getTracker().trackEvent("dns", "resolve", questDomain, 0L);
        } else {
          synchronized (domains) {
            if (domains.contains(questDomain)) {
              continue;
            } else {
              domains.add(questDomain);
            }
          }
          fetchAnswerHTTP(dnsq, udpreq);
        }
      } catch (SocketException e) {
        Log.e(TAG, e.getLocalizedMessage());
        break;
      } catch (NullPointerException e) {
        Log.e(TAG, "Srvsocket wrong", e);
        break;
      } catch (IOException e) {
        Log.e(TAG, e.getLocalizedMessage());
      }
    }
  }

  /** send response to the source */
  private void sendDns(byte[] response, DatagramPacket dnsq, DatagramSocket srvSocket) {

    System.arraycopy(dnsq.getData(), 0, response, 0, 2);

    DatagramPacket resp = new DatagramPacket(response, 0, response.length);
    resp.setPort(dnsq.getPort());
    resp.setAddress(dnsq.getAddress());
    try {
      srvSocket.send(resp);
    } catch (IOException e) {
      Log.e(TAG, "", e);
    }
  }

  private class ReverseDNSServer implements Runnable {
    @Override
    public void run() {
      try {
        serverSocket = new ServerSocket(9090);
      } catch (IOException ignored) {
        return;
      }
      while (serverSocket != null && !serverSocket.isClosed()) {
        Socket client = null;
        try {
          client = serverSocket.accept();
          BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
          PrintWriter out = new PrintWriter(client.getOutputStream());
          String addr = in.readLine();
          String domain = reverseQueryFromCache(addr);
          if (domain == null) {
            out.print("null");
            Log.d(TAG, "reverse query: " + addr + " null");
          } else {
            Log.d(TAG, "reverse query: " + addr + " " + domain);
            out.print(domain);
          }
          out.flush();
          out.close();
          in.close();
          if (client != null) {
            client.close();
          }
        } catch (IOException e) {
          Log.e(TAG, "reverse socket: ", e);
        } finally {
          if (client != null) {
            try {
              client.close();
            } catch (IOException ignored) {
              // Nothing
            }
          }
        }
      }
    }
  }
}
