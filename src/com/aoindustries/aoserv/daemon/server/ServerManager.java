package com.aoindustries.aoserv.daemon.server;

/*
 * Copyright 2002-2011 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.io.CompressedDataInputStream;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.io.IoUtils;
import com.aoindustries.util.StringUtility;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * The <code>ServerManager</code> controls stuff at a server level.
 *
 * @author  AO Industries, Inc.
 */
final public class ServerManager {

    private ServerManager() {
    }

    private static final File procLoadavg = new File("/proc/loadavg");
    private static final File procMeminfo = new File("/proc/meminfo");

    /** One lock per process name */
    private static final Map<String,Object> processLocks=new HashMap<String,Object>();
    public static void controlProcess(String process, String command) throws IOException, SQLException {
        int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
        if(
            osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
        ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

        Object lock;
        synchronized(processLocks) {
            lock=processLocks.get(process);
            if(lock==null) processLocks.put(process, lock=new Object());
        }
        synchronized(lock) {
            String[] cmd={
                "/etc/rc.d/init.d/"+process,
                command
            };
            AOServDaemon.exec(cmd);
        }
    }

    public static void restartCron() throws IOException, SQLException {
        controlProcess("crond", "restart");
    }

    public static void restartXfs() throws IOException, SQLException {
        controlProcess("xfs", "restart");
    }

    public static void restartXvfb() throws IOException, SQLException {
        controlProcess("xvfb", "restart");
    }

    public static void startCron() throws IOException, SQLException {
        controlProcess("crond", "start");
    }

    public static void startXfs() throws IOException, SQLException {
        controlProcess("xfs", "start");
    }

    public static void startXvfb() throws IOException, SQLException {
        controlProcess("xvfb", "start");
    }

    public static void stopCron() throws IOException, SQLException {
        controlProcess("crond", "stop");
    }

    public static void stopXfs() throws IOException, SQLException {
        controlProcess("xfs", "stop");
    }

    public static void stopXvfb() throws IOException, SQLException {
        controlProcess("xvfb", "stop");
    }

    public static String get3wareRaidReport() throws IOException {
        String[] command = {
            "/opt/tw_cli/tw_cli",
            "show"
        };
        return AOServDaemon.execAndCapture(command);
    }

    public static String getMdRaidReport() throws IOException {
        File procFile = new File("/proc/mdstat");
        String report;
        if(procFile.exists()) {
            StringBuilder SB=new StringBuilder();
            InputStream in=new BufferedInputStream(new FileInputStream(procFile));
            try {
                int ch;
                while((ch=in.read())!=-1) SB.append((char)ch);
            } finally {
                in.close();
            }
            report = SB.toString();
        } else report="";
        return report;
    }

    public static String getDrbdReport() throws IOException {
        String[] command = {
            "/opt/aoserv-daemon/bin/drbdcstate"
        };
        return AOServDaemon.execAndCapture(command);
    }

    public static String[] getLvmReport() throws IOException {
        return new String[] {
            AOServDaemon.execAndCapture(
                new String[] {
                    "/usr/sbin/vgs",
                    "--noheadings",
                    "--separator=\t",
                    "--units=b",
                    "-o",
                    "vg_name,vg_extent_size,vg_extent_count,vg_free_count,pv_count,lv_count"
                }
            ),
            AOServDaemon.execAndCapture(
                new String[] {
                    "/usr/sbin/pvs",
                    "--noheadings",
                    "--separator=\t",
                    "--units=b",
                    "-o",
                    "pv_name,pv_pe_count,pv_pe_alloc_count,pv_size,vg_name"
                }
            ),
            AOServDaemon.execAndCapture(
                new String[] {
                    "/usr/sbin/lvs",
                    "--noheadings",
                    "--separator=\t",
                    "-o",
                    "vg_name,lv_name,seg_count,segtype,stripes,seg_start_pe,seg_pe_ranges"
                }
            )
        };
    }

    public static String getHddTempReport() throws IOException {
        return AOServDaemon.execAndCapture(
            new String[] {
                "/opt/aoserv-daemon/bin/hddtemp"
            }
        );
    }

    public static String getHddModelReport() throws IOException {
        return AOServDaemon.execAndCapture(
            new String[] {
                "/opt/aoserv-daemon/bin/hddmodel"
            }
        );
    }

    public static String getFilesystemsCsvReport() throws IOException, SQLException {
        OperatingSystemVersion osvObj = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion();
        if(osvObj==null) return "";
        else {
            int osv = osvObj.getPkey();
            if(
                osv==OperatingSystemVersion.CENTOS_5DOM0_I686
                || osv==OperatingSystemVersion.CENTOS_5DOM0_X86_64
                || osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                || osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
            ) {
                return AOServDaemon.execAndCapture(
                    new String[] {
                        "/opt/aoserv-daemon/bin/filesystemscsv"
                    }
                );
            } else {
                throw new IOException("Unexpected operating system version: "+osv);
            }
        }
    }

    public static String getLoadAvgReport() throws IOException, SQLException {
        StringBuilder report = new StringBuilder(40);
        InputStream in=new BufferedInputStream(new FileInputStream(procLoadavg));
        try {
            int ch;
            while((ch=in.read())!=-1) report.append((char)ch);
        } finally {
            in.close();
        }
        return report.toString();
    }

    public static String getMemInfoReport() throws IOException, SQLException {
        StringBuilder report = new StringBuilder(40);
        InputStream in=new BufferedInputStream(new FileInputStream(procMeminfo));
        try {
            int ch;
            while((ch=in.read())!=-1) report.append((char)ch);
        } finally {
            in.close();
        }
        return report.toString();
    }

    public static class XmListNode {

        /**
         * Parses the result of xm list -l into a tree of objects that have a name
         * and then a list of values.  Each value may either by a String or another list.
         * The root node will have a name of <code>""</code>.
         */
        private static XmListNode parseResult(String result) throws ParseException {
            XmListNode rootNode = new XmListNode("");
            int pos = parseResult(rootNode, result, 0);
            // Everything after pos should be whitespace
            if(result.substring(pos).trim().length()>0) throw new ParseException("Non-whitespace remaining after completed parsing", pos);
            return rootNode;
        }

        /**
         * Parses into the current map
         * @param map
         * @param result
         * @param pos
         * @return
         */
        private static int parseResult(XmListNode node, String result, int pos) throws ParseException{
            int len = result.length();

            while(pos<len) {
                // Look for the next non-whitespace character
                while(pos<len && result.charAt(pos)<=' ') pos++;
                if(pos<len) {
                    if(result.charAt(pos)=='(') {
                        // If is a (, start a sublist
                        int nameStart = ++pos;
                        while(pos<len && result.charAt(pos)>' ') pos++;
                        if(pos>=len) throw new ParseException("Unexpected end of result", pos);
                        String name = result.substring(nameStart, pos);
                        if(name.length()==0) throw new ParseException("Empty name", nameStart);
                        XmListNode newNode = new XmListNode(name);
                        pos = parseResult(newNode, result, pos);
                        if(pos>=len) throw new ParseException("Unexpected end of result", pos);
                        // Character at pos should be )
                        if(result.charAt(pos)!=')') throw new ParseException("')' expected", pos);
                        node.list.add(newNode);
                        pos++; // Skip past )
                    } else {
                        // Is a raw value, parse up to either whitespace or )
                        int valueStart = pos;
                        while(pos<len && (result.charAt(pos)>' ' && result.charAt(pos)!=')')) pos++;
                        if(pos>=len) throw new ParseException("Unexpected end of result", pos);
                        String value = result.substring(valueStart, pos).trim();
                        if(value.length()>0) node.list.add(value);
                        if(result.charAt(pos)==')') {
                            // Found ), end list
                            return pos;
                        }
                    }
                }
            }
            return pos;
        }

        private final String id;
        private final List<Object> list;
        private XmListNode(String id) {
            this.id = id;
            this.list = new ArrayList<Object>();
        }

        public String getString(String childNodeName) throws ParseException {
            for(Object child : list) {
                if(child instanceof XmListNode) {
                    XmListNode childNode = (XmListNode)child;
                    if(childNode.id.equals(childNodeName)) {
                        // Should have a sublist of length 1
                        if(childNode.list.size()!=1) throw new ParseException("child list must have length 1, got "+childNode.list.size(), 0);
                        Object childNodeValue = childNode.list.get(0);
                        if(!(childNodeValue instanceof String)) throw new ParseException("child node list element is not a String", 0);
                        return (String)childNodeValue;
                    }
                }
            }
            throw new ParseException("No child node named '"+childNodeName+"' found", 0);
        }

        public int getInt(String childNodeName) throws ParseException {
            return Integer.parseInt(getString(childNodeName));
        }

        public long getLong(String childNodeName) throws ParseException {
            return Long.parseLong(getString(childNodeName));
        }

        public float getFloat(String childNodeName) throws ParseException {
            return Float.parseFloat(getString(childNodeName));
        }

        public double getDouble(String childNodeName) throws ParseException {
            return Double.parseDouble(getString(childNodeName));
        }
    }

    public static class XmList {

        private final int domid;
        private final String uuid;
        private final int vcpus;
        private final float cpuWeight;
        private final int memory;
        private final int shadowMemory;
        private final int maxmem;
        // features is skipped
        private final String name;
        private final String onPoweroff;
        private final String onReboot;
        private final String onCrash;
        // image is skipped
        // devices are skipped
        private final String state;
        private final String shutdownReason;
        private final double cpuTime;
        private final int onlineCcpus;
        private final double upTime;
        private final double startTime;
        private final long storeMfn;

        XmList(String serverName) throws ParseException, IOException {
            XmListNode rootNode = XmListNode.parseResult(
                AOServDaemon.execAndCapture(
                    new String[] {
                        "/usr/sbin/xm",
                        "list",
                        "-l",
                        serverName
                    }
                )
            );
            // Should have one child
            if(rootNode.list.size()!=1) throw new ParseException("Expected one child of the root node, got "+rootNode.list.size(), 0);
            XmListNode domainNode = (XmListNode)rootNode.list.get(0);
            if(!domainNode.id.equals("domain")) throw new ParseException("Expected only child of the root node to have the id 'domain', got '"+domainNode.id+"'", 0);
            domid = domainNode.getInt("domid");
            uuid = domainNode.getString("uuid");
            vcpus = domainNode.getInt("vcpus");
            cpuWeight = domainNode.getFloat("cpu_weight");
            memory = domainNode.getInt("memory");
            shadowMemory = domainNode.getInt("shadow_memory");
            maxmem = domainNode.getInt("maxmem");
            name = domainNode.getString("name");
            onPoweroff = domainNode.getString("on_poweroff");
            onReboot = domainNode.getString("on_reboot");
            onCrash = domainNode.getString("on_crash");
            state = domainNode.getString("state");
            shutdownReason = domainNode.getString("shutdown_reason");
            cpuTime = domainNode.getDouble("cpu_time");
            onlineCcpus = domainNode.getInt("online_vcpus");
            upTime = domainNode.getDouble("up_time");
            startTime = domainNode.getDouble("start_time");
            storeMfn = domainNode.getLong("store_mfn");
        }

        /**
         * @return the cpuWeight
         */
        public float getCpuWeight() {
            return cpuWeight;
        }

        /**
         * @return the memory
         */
        public int getMemory() {
            return memory;
        }

        /**
         * @return the shadowMemory
         */
        public int getShadowMemory() {
            return shadowMemory;
        }

        /**
         * @return the maxmem
         */
        public int getMaxmem() {
            return maxmem;
        }

        /**
         * @return the name
         */
        public String getName() {
            return name;
        }

        /**
         * @return the onPoweroff
         */
        public String getOnPoweroff() {
            return onPoweroff;
        }

        /**
         * @return the onReboot
         */
        public String getOnReboot() {
            return onReboot;
        }

        /**
         * @return the onCrash
         */
        public String getOnCrash() {
            return onCrash;
        }

        /**
         * @return the state
         */
        public String getState() {
            return state;
        }

        /**
         * @return the shutdownReason
         */
        public String getShutdownReason() {
            return shutdownReason;
        }

        /**
         * @return the cpuTime
         */
        public double getCpuTime() {
            return cpuTime;
        }

        /**
         * @return the onlineCcpus
         */
        public int getOnlineCcpus() {
            return onlineCcpus;
        }

        /**
         * @return the upTime
         */
        public double getUpTime() {
            return upTime;
        }

        /**
         * @return the startTime
         */
        public double getStartTime() {
            return startTime;
        }

        /**
         * @return the storeMfn
         */
        public long getStoreMfn() {
            return storeMfn;
        }

        /**
         * @return the domid
         */
        public int getDomid() {
            return domid;
        }

        /**
         * @return the uuid
         */
        public String getUuid() {
            return uuid;
        }

        /**
         * @return the vcpus
         */
        public int getVcpus() {
            return vcpus;
        }
    }

    /**
     * Finds a PID given its exact command line as found in /proc/.../cmdline
     * Returns PID or <code>-1</code> if not found.
     */
    public static int findPid(String cmdlinePrefix) throws IOException {
        File procFile = new File("/proc");
        String[] list = procFile.list();
        if(list!=null) {
            for(String filename : list) {
                try {
                    File dir = new File(procFile, filename);
                    if(dir.isDirectory()) {
                        try {
                            int pid = Integer.parseInt(filename);
                            File cmdlineFile = new File(dir, "cmdline");
                            if(cmdlineFile.exists()) {
                                StringBuilder SB = new StringBuilder();
                                FileInputStream in = new FileInputStream(cmdlineFile);
                                try {
                                    int b;
                                    while((b=in.read())!=-1) SB.append((char)b);
                                } finally {
                                    in.close();
                                }
                                if(SB.toString().startsWith(cmdlinePrefix)) return pid;
                            }
                        } catch(NumberFormatException err) {
                            // Not a PID directory
                        }
                    }
                } catch(IOException err) {
                    // Log as warning
                    LogFactory.getLogger(ServerManager.class).log(Level.WARNING, null, err);
                }
            }
        }
        return -1;
    }

    /**
     * Parses the output of xm list -l for a specific domain.
     */
    public static void vncConsole(
        final Socket socket,
        final CompressedDataInputStream socketIn,
        CompressedDataOutputStream socketOut,
        String serverName
    ) throws IOException {
        try {
            try {
                try {
                    // Find the ID of the server from xm list
                    XmList xmList = new XmList(serverName);
                    if(!serverName.equals(xmList.getName())) throw new AssertionError("serverName!=xmList.name");
                    int domid = xmList.getDomid();

                    // Find the PID of its qemu handler from its ID
                    int pid = findPid("/usr/lib64/xen/bin/qemu-dm\u0000-d\u0000"+domid+"\u0000"); // Hardware virtualized
                    if(pid==-1) pid = findPid("/usr/lib64/xen/bin/xen-vncfb\u0000--unused\u0000--listen\u0000127.0.0.1\u0000--domid\u0000"+domid+"\u0000"); // Paravirtualized
                    if(pid==-1) throw new IOException("Unable to find PID");

                    // Find its port from lsof given its PID
                    String lsof = AOServDaemon.execAndCapture(
                        new String[] {
                            "/usr/sbin/lsof",
                            "-n",
                            "-P",
                            "-a",
                            "-p",
                            Integer.toString(pid),
                            "-i",
                            "TCP",
                            "-F",
                            "0pPnT"
                        }
                    );
                    String[] values = StringUtility.splitString(lsof, '\u0000');
                    System.out.println("values.length="+values.length);
                    if(
                        values.length<7
                        || (values.length%5)!=2
                        || !values[0].equals("p"+pid)
                        || values[values.length-1].trim().length()!=0
                    ) throw new ParseException("Unexpected output from lsof: "+lsof, 0);
                    int vncPort = Integer.MIN_VALUE;
                    for(int c=1; c<values.length; c+=5) {
                        if(
                            !values[c].trim().equals("PTCP")
                            || !values[c+2].startsWith("TST=")
                            || !values[c+3].startsWith("TQR=")
                            || !values[c+4].startsWith("TQS=")
                        ) {
                            throw new ParseException("Unexpected output from lsof: "+lsof, 0);
                        }
                        if(
                            (values[c+1].startsWith("n127.0.0.1:") || values[c+1].startsWith("n*:"))
                            && values[c+2].equals("TST=LISTEN")
                        ) {
                            vncPort = Integer.parseInt(values[c+1].substring(values[c+1].indexOf(':')+1));
                            break;
                        }
                    }
                    System.out.println("vncPort="+vncPort);
                    if(vncPort==Integer.MIN_VALUE) throw new ParseException("Unexpected output from lsof: "+lsof, 0);

                    // Connect to port and tunnel through all data until EOF
                    final Socket vncSocket = getVncSocket(vncPort);
                    try {
                        InputStream vncIn = vncSocket.getInputStream();
                        try {
                            final OutputStream vncOut = vncSocket.getOutputStream();
                            try {
                                // socketIn -> vncOut in another thread
                                Thread inThread = new Thread(
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                try {
                                                    IoUtils.copy(socketIn, vncOut, true);
                                                } finally {
                                                    vncSocket.close();
                                                }
                                            } catch(ThreadDeath TD) {
                                                throw TD;
                                            } catch(Throwable T) {
                                                LogFactory.getLogger(ServerManager.class).log(Level.SEVERE, null, T);
                                            }
                                        }
                                    }
                                );
                                inThread.start();
                                //try {
                                    // Tell it DONE OK
                                    socketOut.write(AOServDaemonProtocol.NEXT);
                                    // vncIn -> socketOut in this thread
                                    IoUtils.copy(vncIn, socketOut, true);
                                //} finally {
                                    //try {
                                        // Let the in thread complete its work before closing streams
                                    //    inThread.join();
                                    //} catch(InterruptedException err) {
                                    //    IOException ioErr = new InterruptedIOException();
                                    //    ioErr.initCause(err);
                                    //    throw ioErr;
                                    //}
                                //}
                            } finally {
                                vncOut.close();
                            }
                        } finally {
                            vncIn.close();
                        }
                    } finally {
                        closeVncSocket(vncPort, vncSocket);
                    }
                } finally {
                    socketIn.close();
                }
            } finally {
                socketOut.close();
            }
        } catch(ParseException err) {
            IOException ioErr = new IOException();
            ioErr.initCause(err);
            throw ioErr;
        } finally {
            socket.close();
        }
    }

    /**
     * Tracks the open connections to close them when new connections are established.
     */
    private static final Map<Integer,Socket> openVncSockets = new HashMap<Integer,Socket>();

    /**
     * Gets a socket connection to the provided VNC port.  If any connection
     * exists to that port, closes the existing connection and then creates
     * the new connection.  The socket that is returned should be closed using
     * <code>closeVncSocket(Socket)</code> in a try/finally block to ensure
     * minimal data structures.
     *
     * @see  #closeVncSocket
     */
    private static Socket getVncSocket(int vncPort) throws IOException {
        synchronized(openVncSockets) {
            Socket existingSocket = openVncSockets.get(vncPort);
            if(existingSocket!=null) {
                try {
                    existingSocket.close();
                } catch(IOException err) {
                    LogFactory.getLogger(ServerManager.class).log(Level.INFO, null, err);
                }
            }
            Socket vncSocket = new Socket("127.0.0.1", vncPort);
            openVncSockets.put(vncPort, vncSocket);
            return vncSocket;
        }
    }

    /**
     * Closes the socket and removes it from the map of active sockets.
     *
     * @see  #getVncSocket
     */
    private static void closeVncSocket(int vncPort, Socket vncSocket) {
        synchronized(openVncSockets) {
            try {
                vncSocket.close();
            } catch(IOException err) {
                LogFactory.getLogger(ServerManager.class).log(Level.INFO, null, err);
            }
            if(openVncSockets.get(vncPort)==vncSocket) openVncSockets.remove(vncPort);
        }
    }
}
