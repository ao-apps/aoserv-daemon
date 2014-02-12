/*
 * Copyright 2012-2013, 2014 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.server;

import com.aoindustries.aoserv.client.VirtualServer;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.io.CompressedDataInputStream;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.lang.ProcessResult;
import com.aoindustries.util.StringUtility;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.text.ParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * The <code>ServerManager</code> controls stuff at a server level.
 *
 * @author  AO Industries, Inc.
 */
final public class VirtualServerManager {

    private VirtualServerManager() {
    }

    public static class XmList {

        private static int parseState(String state) throws ParseException {
            if(state.length()!=6) throw new ParseException("Unexpected state length: " + state, 0);
            int flags = 0;
            char r = state.charAt(0);
            char b = state.charAt(1);
            char p = state.charAt(2);
            char s = state.charAt(3);
            char c = state.charAt(4);
            char d = state.charAt(5);
            if(r=='r') flags |= VirtualServer.RUNNING;
            else if(r != '-') throw new ParseException("Unexpected character for 'r': " + r, 0);
            if(b=='b') flags |= VirtualServer.BLOCKED;
            else if(b!='-') throw new ParseException("Unexpected character for 'b': "+b, 0);
            if(p=='p') flags |= VirtualServer.PAUSED;
            else if(p!='-') throw new ParseException("Unexpected character for 'p': "+p, 0);
            if(s=='s') flags |= VirtualServer.SHUTDOWN;
            else if(s!='-') throw new ParseException("Unexpected character for 's': "+s, 0);
            if(c=='c') flags |= VirtualServer.CRASHED;
            else if(c!='-') throw new ParseException("Unexpected character for 'c': "+c, 0);
            if(d=='d') flags |= VirtualServer.DYING;
            else if(d!='-') throw new ParseException("Unexpected character for 'd': "+d, 0);
            return flags;
        }

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
        private final int state;
        private final String shutdownReason;
        private final double cpuTime;
        private final int onlineCcpus;
        private final double upTime;
        private final double startTime;
        //private final long storeMfn;

        XmList(String serverName) throws ParseException, IOException {
            XmListNode rootNode = XmListNode.parseResult(
                AOServDaemon.execAndCapture(
					"/usr/sbin/xm",
					"list",
					"-l",
					serverName
                )
            );
            // Should have one child
            if(rootNode.size()!=1) throw new ParseException("Expected one child of the root node, got "+rootNode.size(), 0);
            XmListNode domainNode = (XmListNode)rootNode.get(0);
            if(!domainNode.getId().equals("domain")) throw new ParseException("Expected only child of the root node to have the id 'domain', got '"+domainNode.getId()+"'", 0);
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
            state = parseState(domainNode.getString("state"));
            shutdownReason = domainNode.getString("shutdown_reason");
            cpuTime = domainNode.getDouble("cpu_time");
            onlineCcpus = domainNode.getInt("online_vcpus");
            upTime = domainNode.getDouble("up_time");
            startTime = domainNode.getDouble("start_time");
            //storeMfn = domainNode.getLong("store_mfn");
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
        public int getState() {
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
        //public long getStoreMfn() {
        //    return storeMfn;
        //}

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
                                InputStream in = new BufferedInputStream(new FileInputStream(cmdlineFile), cmdlinePrefix.length());
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
                    LogFactory.getLogger(VirtualServerManager.class).log(Level.WARNING, null, err);
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
                    int         pid = findPid("/usr/lib64/xen/bin/qemu-dm\u0000-d\u0000"+domid+"\u0000"); // Hardware virtualized
                    if(pid==-1) pid = findPid("/usr/lib64/xen/bin/qemu-dm\u0000-M\u0000xenpv\u0000-d\u0000"+domid+"\u0000"); // New Paravirtualized
                    if(pid==-1) pid = findPid("/usr/lib64/xen/bin/xen-vncfb\u0000--unused\u0000--listen\u0000127.0.0.1\u0000--domid\u0000"+domid+"\u0000"); // Old Paravirtualized
                    if(pid==-1) throw new IOException("Unable to find PID");

                    // Find its port from lsof given its PID
                    String lsof = AOServDaemon.execAndCapture(
						"/usr/sbin/lsof",
						"-n", // Numeric IP addresses
						"-P", // Numeric port numbers
						"-a",
						"-p",
						Integer.toString(pid),
						"-i",
						"TCP",
						"-F",
						"0pPnT"
                    );
                    List<String> values = StringUtility.splitString(lsof, '\u0000');
                    System.out.println("values.size()="+values.size());
                    if(
                        values.size()<7
                        || (values.size()%5)!=2
                        || !values.get(0).equals("p"+pid)
                        || values.get(values.size()-1).trim().length()!=0
                    ) throw new ParseException("Unexpected output from lsof: "+lsof, 0);
                    int vncPort = Integer.MIN_VALUE;
                    for(int c=1; c<values.size(); c+=5) {
                        if(
                            !values.get(c).trim().equals("PTCP")
                            || !values.get(c+2).startsWith("TST=")
                            || !values.get(c+3).startsWith("TQR=")
                            || !values.get(c+4).startsWith("TQS=")
                        ) {
                            throw new ParseException("Unexpected output from lsof: "+lsof, 0);
                        }
                        if(
                            (values.get(c+1).startsWith("n127.0.0.1:") || values.get(c+1).startsWith("n*:"))
                            && values.get(c+2).equals("TST=LISTEN")
                        ) {
                            vncPort = Integer.parseInt(values.get(c+1).substring(values.get(c+1).indexOf(':')+1));
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
                                                    byte[] buff = new byte[4096];
                                                    int ret;
                                                    while((ret=socketIn.read(buff, 0, 4096))!=-1) {
                                                        vncOut.write(buff, 0, ret);
                                                        vncOut.flush();
                                                    }
                                                } finally {
                                                    vncSocket.close();
                                                }
                                            } catch(ThreadDeath TD) {
                                                throw TD;
                                            } catch(Throwable T) {
                                                LogFactory.getLogger(VirtualServerManager.class).log(Level.SEVERE, null, T);
                                            }
                                        }
                                    }
                                );
                                inThread.start();
                                //try {
                                    // Tell it DONE OK
                                    socketOut.write(AOServDaemonProtocol.NEXT);
                                    // vncIn -> socketOut in this thread
                                    byte[] buff = new byte[4096];
                                    int ret;
                                    while((ret=vncIn.read(buff, 0, 4096))!=-1) {
                                        socketOut.write(buff, 0, ret);
                                        socketOut.flush();
                                    }
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
    private static final Map<Integer,Socket> openVncSockets = new HashMap<>();

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
                    LogFactory.getLogger(VirtualServerManager.class).log(Level.INFO, null, err);
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
                LogFactory.getLogger(VirtualServerManager.class).log(Level.INFO, null, err);
            }
            if(openVncSockets.get(vncPort)==vncSocket) openVncSockets.remove(vncPort);
        }
    }

    public static String createVirtualServer(String virtualServer) throws IOException {
        ProcessResult result = ProcessResult.exec(
            new String[] {
                "/usr/sbin/xm",
                "create",
                "/etc/xen/guests/"+virtualServer+"/config"
            }
        );
        String stderr = result.getStderr();
        if(result.getExitVal()==0) {
            // Log any errors
            if(stderr.length()>0) System.err.println(stderr);
            return result.getStdout();
        } else {
            throw new IOException(stderr);
        }
    }

    public static String rebootVirtualServer(String virtualServer) throws IOException {
        ProcessResult result = ProcessResult.exec(
            new String[] {
                "/usr/sbin/xm",
                "reboot",
                virtualServer
            }
        );
        String stderr = result.getStderr();
        if(result.getExitVal()==0) {
            // Log any errors
            if(stderr.length()>0) System.err.println(stderr);
            return result.getStdout();
        } else {
            throw new IOException(stderr);
        }
    }

    public static String shutdownVirtualServer(String virtualServer) throws IOException {
        ProcessResult result = ProcessResult.exec(
            new String[] {
                "/usr/sbin/xm",
                "shutdown",
                virtualServer
            }
        );
        String stderr = result.getStderr();
        if(result.getExitVal()==0) {
            // Log any errors
            if(stderr.length()>0) System.err.println(stderr);
            return result.getStdout();
        } else {
            throw new IOException(stderr);
        }
    }

    public static String destroyVirtualServer(String virtualServer) throws IOException {
        ProcessResult result = ProcessResult.exec(
            new String[] {
                "/usr/sbin/xm",
                "destroy",
                virtualServer
            }
        );
        String stderr = result.getStderr();
        if(result.getExitVal()==0) {
            // Log any errors
            if(stderr.length()>0) System.err.println(stderr);
            return result.getStdout();
        } else {
            throw new IOException(stderr);
        }
    }

    public static String pauseVirtualServer(String virtualServer) throws IOException {
        ProcessResult result = ProcessResult.exec(
            new String[] {
                "/usr/sbin/xm",
                "pause",
                virtualServer
            }
        );
        String stderr = result.getStderr();
        if(result.getExitVal()==0) {
            // Log any errors
            if(stderr.length()>0) System.err.println(stderr);
            return result.getStdout();
        } else {
            throw new IOException(stderr);
        }
    }

    public static String unpauseVirtualServer(String virtualServer) throws IOException {
        ProcessResult result = ProcessResult.exec(
            new String[] {
                "/usr/sbin/xm",
                "unpause",
                virtualServer
            }
        );
        String stderr = result.getStderr();
        if(result.getExitVal()==0) {
            // Log any errors
            if(stderr.length()>0) System.err.println(stderr);
            return result.getStdout();
        } else {
            throw new IOException(stderr);
        }
    }

    public static int getVirtualServerStatus(String virtualServer) throws IOException {
        try {
            // Find the ID of the server from xm list
            XmList xmList = new XmList(virtualServer);
            if(!virtualServer.equals(xmList.getName())) throw new AssertionError("virtualServer!=xmList.name");
            return xmList.getState();
        } catch(IOException e) {
            String message = e.getMessage();
            if(message!=null && message.endsWith(" does not exist.")) return VirtualServer.DESTROYED;
            throw e;
        } catch(ParseException err) {
            IOException ioErr = new IOException();
            ioErr.initCause(err);
            throw ioErr;
        }
    }

	public static long verifyVirtualDisk(String virtualServer, String device) throws IOException {
		return Long.parseLong(
			AOServDaemon.execAndCapture(
				"/opt/aoserv-daemon/bin/drbd-verify",
				virtualServer + "-" + device
			).trim()
		);
	}

	public static void updateVirtualDiskLastVerified(String virtualServer, String device, long lastVerified) throws IOException {
		AOServDaemon.exec(
			"/opt/aoserv-daemon/bin/set-drbd-last-verified",
			virtualServer + "-" + device,
			Long.toString(lastVerified)
		);
	}
}
