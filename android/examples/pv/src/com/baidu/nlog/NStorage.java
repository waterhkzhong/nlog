package com.baidu.nlog;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import java.net.Proxy;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.telephony.TelephonyManager;
import android.util.Log;

/* debug start */
//import android.os.Environment;
/* debug end */

@SuppressLint({ "HandlerLeak", "DefaultLocale" })
public class NStorage {
    /**
     * nlog
     * @description Nativeͳ�ƿ�ܣ�����洢������ͨ��
     * @author ������(WangJihu,http://weibo.com/zswang),����ɽ(PengZhengshan)
     * @see https://github.com/uxrp/nlog/wiki/design
     * @version 1.0
     * @copyright www.baidu.com
     */
    /**
     *  ��־TAG
     */
    private static String LOGTAG = "NStorage";

    /**
     * �豸id
     */
    private static String deviceId = "";
    
    /**
     * �������ӳ�ʱ,��λ������
     */
    private static final int connTimeout = 40 * 1000; // 40��
    /**
     * ��ȡ���ӳ�ʱ,��λ������
     */
    private static final int readTimeout = 60 * 1000; //60�� ��ȡ���ݳ�ʱ
    /*
     * �洢�ļ��汾 // ���������ļ��汾�ı�
     */
    public static final String fileVersion = "0";
    
    /**
     * ���滺���ļ���Ŀ¼
     */
    private static String rootDir = null; // init�г�ʼ��
    /**
     * �����ļ���
     */
    private static String ruleFilename = null; // init�г�ʼ��
    
    /**
     * �����ļ���ģ�� _nlog_[version]_[itemname].dat, itemname => [name].[md5(head)]
     */
    private static String cacheFileFormat = null; // ini�г�ʼ��
        
    /**
     * ������
     */
    private static class CacheItem {
        public StringBuffer sb;
        public String name;
        public String head;
        public byte[] pass;
        /**
         * ����
         * @param name ���� 
         * @param head ����
         * @param sb �ַ�����
         */
        CacheItem(String name, String head) {
            this.sb = new StringBuffer();
            this.head = head;
            this.name = name;

            this.pass = buildPass(name);
        }
    }
    
    /**
     * ������
     */
    private static class PostItem {
        public String name;
        public byte[] pass;
        public String locked;
        /**
         * ����
         * @param name ����
         * @param locked �����ļ���
         */
        PostItem(String name, String locked) {
            this.name = name;
            this.locked = locked;
            this.pass = buildPass(name);
        }
    }
    
    /**
     * �ļ���Կ������ʵ���������޸ĳ��Լ���
     */
    private static String secretKey = "5D97EEF8-3127-4859-2222-82E6C8FABD8A";
    
    /**
     * ��Կ�����棬Ϊ�������ٶ�
     */
    private static Map<String, byte[]> passMap = new HashMap<String, byte[]>(); 
    
    /**
     * ������Կ����������޸���Ҫ����fileVersion
     * @param name ����
     * @return ������Կ��
     */
    private static byte[] buildPass(String name) {
        byte[] result = passMap.get(name);
        if (result != null) return result;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(String.format("%s,%s,%s", deviceId, name, secretKey).getBytes());
            baos.write(md.digest());
            md.update(String.format("%s,%s,%s", name, deviceId, secretKey).getBytes());
            baos.write(md.digest());
            md.update(String.format("%s,%s,%s", deviceId, secretKey, name).getBytes());
            baos.write(md.digest());
            result = baos.toByteArray(); 
            baos.close();
            passMap.put(name, result);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * ������־
     */
    private static Map<String, CacheItem> cacheItems = new HashMap<String, CacheItem>();
    
    /**
     * �����ֶ�����д�������дΪnull�������
     * @param protocolParameter �ֶ����ֵ�
     * @param map ��������
     * @return ���ش�������ֵ�
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> runProtocolParameter(Object protocolParameter, Map<String, Object> map) {
        if (protocolParameter == null || (!(protocolParameter instanceof Map))) {
            return map;
        }
        Map<String, Object> parameter = (HashMap<String, Object>)protocolParameter;
        Map<String, Object> result = new HashMap<String, Object>();
        for (String key : map.keySet()) {
            if (parameter.containsKey(key)) { // ��Ҫת��
                Object newKey = parameter.get(key);
                if (newKey != null) { // Ϊnullʱ����
                    result.put((String)newKey, map.get(key));
                }
            } else {
                result.put(key, map.get(key));
            }
        }
        return result;
    }

    
    private static class ReportParamItem {
        public String trackerName;
        public Map<String, Object> fields;
        public Map<String, Object> data;
        ReportParamItem(String trackerName, Map<String, Object> fields, Map<String, Object> data) {
            this.trackerName = trackerName;
            this.fields = fields;
            this.data = data;
        }
    }
    private static ArrayList<ReportParamItem> reportParamList = new ArrayList<ReportParamItem>();
    
    /**
     * �ϱ�����
     * @param trackerName ׷��������
     * @param fields �����ֶ�
     * @param data �ϱ�����
     */
    public static void report(String trackerName, Map<String, Object> fields, Map<String, Object> data) {
        /* debug start */
        Log.d(LOGTAG, String.format("report('%s', %s, %s) postUrl='%s'", trackerName, fields, data, fields.get("postUrl")));
        /* debug end */
        if (!initCompleted) {
            /* debug start */
            Log.w(LOGTAG, String.format("report() uninitialized."));
            /* debug end */
            reportParamList.add(new ReportParamItem(trackerName, fields, data));
            return;
        }
        String postUrl = (String)fields.get("postUrl");
        if (fields.get("postUrl") == null) {
            // ���ͱ�ȡ��
            /* debug start */
            Log.w(LOGTAG, String.format("report() postUrl is null."));
            /* debug end */
            return;
        }
        Object parameter = fields.get("protocolParameter");
        // ת��͹���
        Map<String, Object> headMap = runProtocolParameter(parameter, fields);
        Map<String, Object> lineMap = runProtocolParameter(parameter, data);

        String separator = "&";
        if (postUrl.indexOf("?") < 0) { // ����url�д��ڡ����������
            separator = "?";
        }
        appendCache(trackerName, postUrl + separator + NLog.buildPost(headMap), NLog.buildPost(lineMap));
    }
    
    /**
     * ������Ϣ
     */
    private static Map<String, Message> messages = new HashMap<String, Message>();
    
    /**
     * �����ݷŵ�������
     * @param trackerName ׷��������
     * @param head �������ݣ���������
     * @param line ÿ������
     */
    private static void appendCache(String trackerName, String head, String line) {
        /* debug start */
        Log.d(LOGTAG, String.format("appendCache('%s', '%s', '%s')", trackerName, head, line));
        /* debug end */

        synchronized(cacheItems) {
            String itemname = String.format("%s.%s", trackerName, getMD5(head));
            CacheItem item = cacheItems.get(itemname);
            if (item == null) {
                item = new CacheItem(itemname, head);
                cacheItems.put(itemname, item); // ���뻺��
            }
            synchronized(item.sb) {
                item.sb.append(line + '\n');
            }
            sendMessage_saveFile(item);
        }
    }
    /** 
     * �ж�Network�Ƿ����ӳɹ�(�����ƶ������wifi) 
     * @return �����Ƿ�����
     */
    public static boolean isNetworkConnected(){ 
        return checkWifiConnected() || checkNetConnected(); 
    }
    
    /**
     * ����ƶ������Ƿ�����
     * @return �����Ƿ�����
     */
    public static boolean checkNetConnected() {
        ConnectivityManager connectivityManager = (ConnectivityManager)NLog.getContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager
                .getActiveNetworkInfo();
        if (networkInfo != null) {
            return networkInfo.isConnected();
        }
        return false;
    }
    
    /**
     * ���wifi�Ƿ�����
     * @return �����Ƿ�����
     */
    public static boolean checkWifiConnected() {
        ConnectivityManager connectivityManager = (ConnectivityManager)NLog.getContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo wiFiNetworkInfo = connectivityManager
                .getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        if (wiFiNetworkInfo != null) {
            return wiFiNetworkInfo.isConnected();
        }
        return false;
    }
    
    /**
     * ���ļ�����
     * @param itemname ����
     * @return ������������ļ���
     */
    private static String buildLocked(String itemname) {
        String filename = String.format(cacheFileFormat, fileVersion, itemname);
        File file = new File(filename);
        if (!file.exists()) return null;
        String result = filename.replaceFirst("\\.dat$", "." + Long.toString(System.currentTimeMillis(), 36) + ".locked");
        File locked = new File(result);
        while (!file.renameTo(locked)) {
            result = filename.replaceFirst("\\.dat$", "." + Long.toString(System.currentTimeMillis(), 36) + ".locked");
            locked = new File(result);
        }
        return result;
    }

    @SuppressLint("DefaultLocale")
    private static Boolean loadRule() {
        Boolean result = false;
        
        if (!isNetworkConnected()) {
            /* debug start */
            Log.d(LOGTAG, String.format("loadRule() - Without a network connection."));
            /* debug end */
            return result;
        }
        
        String ruleUrl = (String)NLog.get("ruleUrl");
        if (ruleUrl == null) {
            return result;
        }
        
        HttpURLConnection conn = null;
        ConnectivityManager conManager = (ConnectivityManager) NLog.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo mobile = conManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
        NetworkInfo wifi = conManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        try {
            Proxy proxy = null;
            if (wifi != null && wifi.isAvailable()) {
                /* debug start */
                Log.d(LOGTAG, "WIFI is available");
                /* debug end */
            } else if (mobile != null && mobile.isAvailable()) {
                String apn = mobile.getExtraInfo().toLowerCase();
                /* debug start */
                Log.d(LOGTAG, "apn = " + apn);
                /* debug end */
                if (apn.startsWith("cmwap") || apn.startsWith("uniwap") || apn.startsWith("3gwap")) {
                    proxy = new Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress("10.0.0.172", 80));
                } else if (apn.startsWith("ctwap")) {
                    proxy = new Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress("10.0.0.200", 80));
                }
            } else { //@fixed in TV
                /* debug start */
                Log.d(LOGTAG, "getConnection:not wifi and mobile");
                /* debug end */
            }
            
            URL url;
            url = new URL(ruleUrl);
            if (proxy == null) {
                conn = (HttpURLConnection)url.openConnection();
            } else {
                conn = (HttpURLConnection)url.openConnection(proxy);
            }
            conn.setConnectTimeout(connTimeout);
            conn.setReadTimeout(readTimeout);
            
            // POST��ʽ
            conn.setDoOutput(false);
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(true);
            conn.connect();
            
            
            InputStream is = conn.getInputStream();
            byte[] buffer = new byte[1024];
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Integer len;
            while ((len = is.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }
            baos.close();
            is.close();
            
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                buffer = baos.toByteArray();
                FileOutputStream fos = new FileOutputStream(ruleFilename);
                fos.write(buffer);
                fos.close();
                NLog.updateRule(new String(buffer));
                result = true;
            }
            conn.disconnect();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            conn.disconnect();
            conn = null;
        }       
        return result;
    }
    /**
     * �����ļ�
     * @param item ������
     * @param lockedname �����ļ���
     * @return �Ƿ��ͳɹ�
     */
    @SuppressLint("DefaultLocale")
    private static Boolean postFile(PostItem item) {
        /* debug start */
        Log.d(LOGTAG, String.format("postFile('%s', '%s')", item.name, item.locked));
        /* debug end */
        
        Boolean result = false;
        if (NLog.safeBoolean(NLog.get("onlywifi"), false) && !checkWifiConnected()) {
            /* debug start */
            Log.d(LOGTAG, String.format("postFile() - Without a wifi connection. onlywifi = true"));
            /* debug end */
            return result;
        } else if (!isNetworkConnected()) {
            /* debug start */
            Log.d(LOGTAG, String.format("postFile() - Without a network connection."));
            /* debug end */
            return result;
        }
        
        String filename = item.locked == null ? String.format(cacheFileFormat, fileVersion, item.name) : item.locked;
        File file = new File(filename);
        if (!file.exists() || file.length() <= 0) {
            Log.w(LOGTAG, String.format("postFile() - file '%s' not found.", filename));
            return result;
        }
        
        byte[] pass = item.pass;
        int len;
        int size = 1024;

        byte[] buf = new byte[size];
        String postUrl = null;
        byte[] gzipBytes = null;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            GZIPOutputStream gos = new GZIPOutputStream(baos);
            FileInputStream fis;
            fis = new FileInputStream(filename);
            Integer offset = 0;
            Boolean existsHead = false;
            Boolean processHead = false;
            while ((len = fis.read(buf, 0, size)) != -1) {
                int t = 0;
                for (int i = 0; i < len; i++) {
                    buf[i] = (byte)(buf[i] ^ offset % 256 ^ pass[offset % pass.length]); // ����
                    offset++;
                    if (!existsHead) {
                        if (buf[i] == '\n') {
                            t = i;
                            postUrl = new String(buf, 0, i);
                            existsHead = true;
                            processHead = true;
                        }
                    }
                }
                if (processHead) { // ��Ҫ����ͷ��Ϣ��������һ��ΪpostUrl�Ͳ���

                    gos.write(buf, t + 1, len - t - 1);
                    processHead = false;
                } else {

                    gos.write(buf, 0, len);
                }
            }
            fis.close();
            fis = null;
            gos.flush();
            gos.finish();
            gos.close();
            gos = null;
            
            /* debug start */
            Log.d(LOGTAG, String.format("postFile() - postUrl = %s.", postUrl));
            /* debug end */
            
            if (postUrl == null) {
                return result;
            }
            gzipBytes = baos.toByteArray();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        HttpURLConnection conn = null;
        ConnectivityManager conManager = (ConnectivityManager) NLog.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo mobile = conManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
        NetworkInfo wifi = conManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        try {
            Proxy proxy = null;
            if (wifi != null && wifi.isAvailable()) {
                /* debug start */
                Log.d(LOGTAG, "WIFI is available");
                /* debug end */
            } else if (mobile != null && mobile.isAvailable()) {
                String apn = mobile.getExtraInfo().toLowerCase();
                /* debug start */
                Log.d(LOGTAG, "apn = " + apn);
                /* debug end */
                if (apn.startsWith("cmwap") || apn.startsWith("uniwap") || apn.startsWith("3gwap")) {
                    proxy = new Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress("10.0.0.172", 80));
                } else if (apn.startsWith("ctwap")) {
                    proxy = new Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress("10.0.0.200", 80));
                }
            } else { //@fixed in TV
                /* debug start */
                Log.d(LOGTAG, "getConnection:not wifi and mobile");
                /* debug end */
            }
            URL url;

            url = new URL(postUrl);
            if (proxy == null) {
                conn = (HttpURLConnection)url.openConnection();
            } else {
                conn = (HttpURLConnection)url.openConnection(proxy);
            }
            conn.setConnectTimeout(connTimeout);
            conn.setReadTimeout(readTimeout);
            
            conn.setDoOutput(true); // POST��ʽ
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);

            /* include connection */
            
            conn.setRequestProperty("Content-Type", "gzip");
            conn.connect();
            
            String lockedname = item.locked;
            if (lockedname == null) { // ��Ҫ�����ļ�
                lockedname = buildLocked(item.name);
                /* debug start */
                Log.d(LOGTAG, String.format("postFile() '%s' locked.", lockedname));
                /* debug end */
            }
            File locked = new File(lockedname);
            if (!locked.exists()) {
                /* debug start */
                Log.d(LOGTAG, String.format("postFile() '%s' locked not exists.", lockedname));
                /* debug end */
                return result;
            }

            /* debug start */
            Log.d(LOGTAG, String.format("postFile() '%s' post start.", lockedname));
            /* debug end */
            
            DataOutputStream dos = new DataOutputStream(conn.getOutputStream());
            dos.write(gzipBytes);
            dos.flush();
            dos.close();
            
            /*
            // ��ȡ����
            InputStream is = conn.getInputStream();
            byte[] buffer = new byte[1024];
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            while ((len = is.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }
            baos.close();
            baos = null;
            is.close();
            */
            
            Integer rc = conn.getResponseCode();
            
            /* debug start */
            Log.d(LOGTAG, String.format("postFile() code = '%s' post end.", rc));
            /* debug end */
            
            // �����������װһ��BufferReader����߶���Ч�� getInputStream���ص����ֽ���������ת�����ַ���
            if (rc == HttpURLConnection.HTTP_OK) {
                // �����ɹ�
                result = true;
                locked.delete();
                /* debug start */
                Log.d(LOGTAG, "post success!");
                /* debug end */
                if (lastScanExists) { // �ϴ�ɨ�����δ���͵��ļ� // ������ʼɨ��
                    sendMessage_scanDir();
                }
            }
            conn.disconnect();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
            conn.disconnect();
            conn = null;
        }
        return result;
    }
    /**
     * ���������Ϊ�ļ������֮ǰ�����ļ���׷��д��
     * @param item
     * @return
     */
    public static Boolean saveFile(CacheItem item) {
        if (item == null) {
            return false;
        }
        String filename = String.format(cacheFileFormat, fileVersion, item.name);
        /* debug start */
        Log.d(LOGTAG, String.format("saveFile() filename : %s", filename));
        /* debug end */
        
        Integer sendMaxLength = NLog.getInteger("sendMaxLength");
        Boolean result = false;
        synchronized(item) {
            try {
                File file = new File(filename);
                int offset = 0;
                byte[] linesBuffer;
                if (file.exists()) {
                    offset = (int)file.length();
                }
                if (offset >= sendMaxLength * 1024) { // �ļ�������Χ���������ļ�
                    buildLocked(item.name); // ��֮ǰ���ļ�����
                    offset = 0;
                }
                if (offset <= 0) { // �ļ������� // ͷ����д
                    linesBuffer = (item.head + '\n' + item.sb.toString()).toString().getBytes();
                } else {
                    linesBuffer = item.sb.toString().getBytes();
                }
                byte[] pass = item.pass;
                if (pass != null && pass.length > 0) { // ��Ҫ����
                    for (int i = 0, len = linesBuffer.length; i < len; i++) {
                        int t = (int) (i + offset);
                        linesBuffer[i] = (byte)(linesBuffer[i] ^ t % 256 ^ pass[t % pass.length]); 
                    }
                }
                @SuppressWarnings("resource")
                FileOutputStream fos = new FileOutputStream(filename, true);
                fos.write(linesBuffer);
                fos.flush();
                item.sb.delete(0, item.sb.length()); // �������
                result = true;
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } 
        }
        return result;
    }
        
    /**
     * ��ȡmd5�ַ���
     * @param text �ı�
     * @return ����Сдmd5���к�
     */
    public static String getMD5(String text) {
        String result = null;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(text.getBytes());
            StringBuffer sb = new StringBuffer();
            for (byte b : md.digest()) {
                sb.append(Integer.toHexString(((int)b & 0xff) + 0x100).substring(1));
            }
            result = sb.toString();

        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return result;
    }
    /**
     * �����洢�ľ��
     */
    private static StorageHandler storageHandler;
    
    /**
     * ��ʼ��Ŀ¼��Ϣ
     */
    private static final byte MESSAGE_INIT = 1;
    
    /**
     * ����Ϊ�ļ�����Ϣ
     * @param obj item
     */
    private static final byte MESSAGE_SAVEFILE = 2;
    
    /**
     * �ϱ��ļ�
     * @param obj item
     */
    private static final byte MESSAGE_POSTFILE = 3;
    
    /**
     * ɨ��Ŀ¼
     */
    private static final byte MESSAGE_SCANDIR = 4;
    /**
     * ɨ��Ŀ¼
     */
    private static Boolean scanDir() {
        Boolean result = false;
        lastScanTime = System.currentTimeMillis();
        lastScanExists = false;
        try {
            File file = new File(rootDir + File.separatorChar);
            for (File subFile : file.listFiles()) {
                /* debug start */
                Log.d(LOGTAG, String.format("file : %s(%sbyte).", subFile.getName(), subFile.length()));
                /* debug end */
                
                Matcher matcher = dataFilePattern.matcher(subFile.getName());
                if (!matcher.find()) { // ������nlog�ļ���
                    continue;
                }
                
                // ���ݹ���ʱ��
                Integer storageExpires = NLog.getInteger("storageExpires");
                if (System.currentTimeMillis() - subFile.lastModified() >= storageExpires * 24 * 60 * 60 * 1000) {
                    /* debug start */
                    Log.d(LOGTAG, String.format("del file : %s(%sbyte).", subFile.getName(), subFile.length()));
                    /* debug end */
                    subFile.delete();
                    continue;
                }
                
                String version = matcher.group(1);
                String itemname = matcher.group(2); // ����
                String extname = matcher.group(4); // ��չ��
                if (!fileVersion.equals(version)) { // �����ݵİ汾
                    /* debug start */
                    Log.d(LOGTAG, String.format("del file : %s(%sbyte).", subFile.getName(), subFile.length()));
                    /* debug end */
                    subFile.delete();
                    continue;
                }
                
                // ��ʼ�����ļ�
                if (sendMessage_postFile(new PostItem(itemname, "locked".equals(extname) ? subFile.getAbsolutePath() : null))) { // ���ͳɹ�
                    lastScanExists = true;
                    result = true;
                    break;
                }
            }
        } catch(Exception e) {
            /* debug start */
            e.printStackTrace();
            /* debug end */
        }
        return result;
    }
    /**
     * �����洢�ľ��
     */
    private static class StorageHandler extends Handler {
        StorageHandler(Looper looper) {
            super(looper);
        }
        
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_SCANDIR:
                    synchronized(messages) { // �����Ϣ
                        String msgName = String.format("%s", MESSAGE_SCANDIR);
                        messages.put(msgName, null);
                    }
                    scanDir();
                    break;
                case MESSAGE_INIT:
                    /* debug start */
                    Log.d(LOGTAG, String.format("case MESSAGE_INIT"));
                    /* debug end */
                    try {
                        File file = new File(rootDir + File.separatorChar);
                        if (!file.exists()) {
                            file.mkdirs();
                        }
                        File ruleFile = new File(ruleFilename);
                        if (ruleFile.exists() && 
                                System.currentTimeMillis() - ruleFile.lastModified() >= 
                                NLog.getInteger("ruleExpires") * 24 * 60 * 60 * 1000) {
                            // ���ڲ���û����
                            try {
                                FileInputStream fis = new FileInputStream(ruleFilename);
                                Integer len = fis.available();
                                byte[] buffer = new byte[len];
                                fis.read(buffer);
                                String ruleText = new String(buffer);
                                fis.close();
                                NLog.updateRule(ruleText);
                                
                                /* debug start */
                                Log.d(LOGTAG, String.format("read '%s'\n===body====\n%s", ruleFilename, ruleText));
                                /* debug end */
                                
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        } else {
                            loadRule();
                        }
                    } catch(Exception e) {
                        /* debug start */
                        e.printStackTrace();
                        /* debug end */
                    }
                    break;
                case MESSAGE_SAVEFILE: {
                    /* debug start */
                    Log.d(LOGTAG, String.format("case MESSAGE_SAVEFILE"));
                    /* debug end */
                    
                    if (msg.obj == null) {
                        break;
                    }
                    
                    CacheItem cacheItem = (CacheItem)msg.obj;
                    synchronized(messages) { // �����Ϣ
                        String msgName = String.format("%s.%s", cacheItem.name, MESSAGE_SAVEFILE);
                        messages.put(msgName, null);
                    }
                    saveFile(cacheItem); // ����� item.sb����
                    sendMessage_postFile(new PostItem(cacheItem.name, null));
                    break;
                }
                case MESSAGE_POSTFILE: {
                    PostItem postItem = (PostItem)msg.obj;
                    /* debug start */
                    Log.d(LOGTAG, String.format("case MESSAGE_POSTFILE locked: %s", postItem.locked));
                    /* debug end */
                    synchronized(messages) { // �����Ϣ
                        String msgName = String.format("%s.%s.%s", postItem.name, MESSAGE_POSTFILE, postItem.locked != null);
                        messages.put(msgName, null);
                    }
                    postFile(postItem);
                    break;
                }
            }
        }

    }
    /**
     * ��ʱ������־
     */
    private static Timer sendTimer = null;
    
    /**
     * �ȴ����͵��ļ�
     */
    private static Pattern dataFilePattern = Pattern.compile("\\b_nlog(?:_(\\d+))?_(\\w+\\.[a-f0-9]{32})(?:\\.([a-z0-9]+))?\\.(locked|dat)$");
    // '_nlog_1_wenku.01234567890123456789012345678901.h0123456.locked'
    // '_nlog_1_wenku.01234567890123456789012345678901.dat'

    /**
     * ����ɨ��Ŀ¼����Ϣ
     * @return �����Ƿ�����Ϣ
     */
    private static Boolean sendMessage_scanDir() {
        Boolean result = false;
        synchronized(messages) { // ��Ϣ���ڷ��͵�;��
            String msgName = String.format("%s", MESSAGE_SCANDIR);
            Message m = messages.get(msgName);
            if (m == null) { // �Ƿ�����ͬ����Ϣ�ڴ���
                m = storageHandler.obtainMessage(MESSAGE_SCANDIR, 0, 0, null);
                storageHandler.sendMessageDelayed(m, 5000);
                messages.put(msgName, m);
                result = true;
                /* debug start */
                Log.d(LOGTAG, String.format("MESSAGE_SCANDIR '%s' message send", msgName));
                /* debug end */
            } else {
                /* debug start */
                Log.d(LOGTAG, String.format("MESSAGE_SCANDIR message sending..."));
                /* debug end */
            }
        }
        return result;
    }
    /**
     * �����ύ�ļ�����Ϣ
     * @param item �ύ�item{ name, locked }
     * @return �����Ƿ�����Ϣ
     */
    private static Boolean sendMessage_postFile(PostItem item) {
        Boolean result = false;
        synchronized(messages) { // ��Ϣ���ڷ��͵�;��
            String msgName = String.format("%s.%s.%s", item.name, MESSAGE_POSTFILE, item.locked != null);
            Message m = messages.get(msgName);
            if (m == null) { // �Ƿ�����ͬ����Ϣ�ڴ���
                m = storageHandler.obtainMessage(MESSAGE_POSTFILE, 0, 0, item);
                storageHandler.sendMessageDelayed(m, 5000);
                messages.put(msgName, m);
                result = true;
                /* debug start */
                Log.d(LOGTAG, String.format("MESSAGE_POSTFILE '%s' message send", msgName));
                /* debug end */
            } else {
                /* debug start */
                Log.d(LOGTAG, String.format("MESSAGE_POSTFILE message sending..."));
                /* debug end */
            }
        }
        return result;
    }
    
    /**
     * ���ͱ����ļ�����Ϣ
     * @param item �ύ�item{ name }
     * @return �����Ƿ�����Ϣ
     */
    private static Boolean sendMessage_saveFile(CacheItem item) {
        Boolean result = false;
        synchronized(messages) { // ��Ϣ���ڷ��͵�;��
            String msgName = String.format("%s.%s", item.name, MESSAGE_SAVEFILE);
            Message m = messages.get(msgName);
            if (m == null) { // �Ƿ�����ͬ����Ϣ�ڴ���
                m = storageHandler.obtainMessage(MESSAGE_SAVEFILE, 0, 0, item);
                storageHandler.sendMessageDelayed(m, 100);
                messages.put(msgName, m);
                result = true;
                /* debug start */
                Log.d(LOGTAG, String.format("MESSAGE_SAVEFILE '%s' message send", msgName));
                /* debug end */
            } else {
                /* debug start */
                Log.d(LOGTAG, String.format("MESSAGE_SAVEFILE message sending..."));
                /* debug end */
            }
        }
        return result;
    }

    /**
     * �Ƿ��Ѿ���ʼ��
     */
    private static Boolean initCompleted = false;
    public static Boolean getInitCompleted() {
        return initCompleted;
    }
    
    /**
     * ���һ��ɨ��Ŀ¼��ʱ��
     */
    private static Long lastScanTime = 0l;
    /**
     * ���һ��ɨ���Ƿ����δ���͵��ļ�
     */
    private static Boolean lastScanExists = false;
    /**
     * ��ʼ��
     * @throws  
     */
    public static void init() {
        if (initCompleted) {
            Log.w(NLog.class.getName(), "init() Can't repeat initialization.");
            return;
        }
        rootDir = NLog.getContext().getFilesDir() + File.separator + "_nlog_cache";
        
        /* debug start */
        //rootDir = Environment.getExternalStorageDirectory() + File.separator + "_nlog_cache"; // �ŵ�SD���������
        /* debug end */
        
        ruleFilename = rootDir + File.separator + "rules.dat";
        cacheFileFormat = rootDir + File.separator + "_nlog_%s_%s.dat";

        initCompleted = true;
        
        TelephonyManager tm = (TelephonyManager)NLog.getContext().getSystemService(Context.TELEPHONY_SERVICE);
        deviceId = tm.getDeviceId();
        
        HandlerThread handlerThread = new HandlerThread("NSTORAGE_HANDLER",
                Process.THREAD_PRIORITY_BACKGROUND);
        handlerThread.start();
        storageHandler = new StorageHandler(handlerThread.getLooper());    
        Message msg = storageHandler.obtainMessage(MESSAGE_INIT);
        storageHandler.sendMessageDelayed(msg, 100);
        
        for (ReportParamItem item : reportParamList) {
            report(item.trackerName, item.fields, item.data);
        }
        reportParamList.clear();
        reportParamList = null;
        
        sendTimer = new Timer();
        sendTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Long now = System.currentTimeMillis();
                Integer sendInterval = NLog.getInteger("sendInterval");
                Integer sendIntervalWifi = NLog.getInteger("sendIntervalWifi");
                if (!lastScanExists) { // �ϴ�ûɨ�赽�ļ��������ӳ�
                    sendInterval += (int)(sendInterval * 1.5);
                    sendIntervalWifi += (int)(sendIntervalWifi * 1.5);
                }
                Boolean onlywifi = NLog.safeBoolean(NLog.get("onlywifi"), false);
                if (now - lastScanTime < Math.min(sendInterval, sendIntervalWifi) * 1000) {
                    return;
                }
                
                // ����������
                if (!isNetworkConnected()) {
                    return;
                } else if (checkWifiConnected()) {
                    if (now - lastScanTime < sendIntervalWifi * 1000) { // wifi
                        return;
                    }
                } else {
                    if (onlywifi) { // ֻ��wifi��
                        return;
                    }
                    if (now - lastScanTime < sendInterval * 1000) { //wifi
                        return;
                    }
                }
                sendMessage_scanDir();
            }
        }, 60000, 60000);

    }
}