/*
 * Copyright 2012-2013, 2014, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.server;

import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.VirtualServer;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.io.CompressedDataInputStream;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.lang.ProcessResult;
import com.aoindustries.util.StringUtility;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.Socket;
import java.net.SocketException;
import java.sql.SQLException;
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
		private final int cpuWeight;
		private final long memory;
		private final long shadowMemory;
		private final long maxmem;
		private final String name;
		private final String onReboot;
		private final int state;

		XmList(String serverName) throws ParseException, IOException, SQLException {
			OperatingSystemVersion osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId == OperatingSystemVersion.CENTOS_5_DOM0_I686
				|| osvId == OperatingSystemVersion.CENTOS_5_DOM0_X86_64
			) {
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
				// cpu_cap is skipped
				cpuWeight = domainNode.getInt("cpu_weight");
				memory = domainNode.getLong("memory");
				shadowMemory = domainNode.getLong("shadow_memory");
				maxmem = domainNode.getLong("maxmem");
				// features is skipped
				name = domainNode.getString("name");
				// on_poweroff is skipped
				onReboot = domainNode.getString("on_reboot");
				// on_crash is skipped
				// image is skipped
				// cpus is skipped
				// devices are skipped
				state = parseState(domainNode.getString("state"));
				// shutdown_reason is skipped
				// cpu_time is skipped
				// online_vcpus is skipped
				// up_time is skipped
				// start_time is skipped
				// store_mfn is skipped
				// console_mfn is skipped
			} else if(osvId == OperatingSystemVersion.CENTOS_7_DOM0_X86_64) {
				// https://stackoverflow.com/questions/21014407/json-array-in-hashmap-using-google-gson
				Type type = new TypeToken<List<Map<String, Object>>>(){}.getType();
				List<Map<String, Object>> rootList = new Gson().fromJson(
					AOServDaemon.execAndCapture(
						"/sbin/xl",
						"list",
						"--long",
						serverName
					),
					type
				);
				// Should have one child
				if(rootList.size()!=1) throw new ParseException("Expected one element in the root array, got "+rootList.size(), 0);
				Map<String, Object> domainNode = rootList.get(0);
				//for(Map.Entry<String,Object> entry : domainNode.entrySet()) {
				//	System.out.println(entry.getKey() + ": (" + entry.getValue().getClass() + ") " + entry.getValue());
				//}
				domid = ((Double)domainNode.get("domid")).intValue();
				@SuppressWarnings("unchecked")
				Map<String, Object> configNode = (Map<String, Object>)domainNode.get("config");
				@SuppressWarnings("unchecked")
				Map<String, Object> cInfoNode = (Map<String, Object>)configNode.get("c_info");
				uuid = (String)cInfoNode.get("uuid");
				@SuppressWarnings("unchecked")
				Map<String, Object> bInfoNode = (Map<String, Object>)configNode.get("b_info");
				vcpus = ((Double)bInfoNode.get("max_vcpus")).intValue();
				@SuppressWarnings("unchecked")
				Map<String, Object> schedParamsNode = (Map<String, Object>)bInfoNode.get("sched_params");
				cpuWeight = ((Double)schedParamsNode.get("weight")).intValue();
				memory = ((Double)bInfoNode.get("target_memkb")).longValue();
				shadowMemory = ((Double)bInfoNode.get("shadow_memkb")).longValue();
				maxmem = ((Double)bInfoNode.get("max_memkb")).longValue();
				name = (String)cInfoNode.get("name");
				onReboot = (String)configNode.get("on_reboot");
				state = 0; // TODO: state not in long listing parseState(domainNode.getString("state"));
			} else {
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
			}
		}

		/**
		 * @return the cpuWeight
		 */
		public int getCpuWeight() {
			return cpuWeight;
		}

		/**
		 * @return the memory
		 */
		public long getMemory() {
			return memory;
		}

		/**
		 * @return the shadowMemory
		 */
		public long getShadowMemory() {
			return shadowMemory;
		}

		/**
		 * @return the maxmem
		 */
		public long getMaxmem() {
			return maxmem;
		}

		/**
		 * @return the name
		 */
		public String getName() {
			return name;
		}

		/**
		 * @return the onReboot
		 */
		public String getOnReboot() {
			return onReboot;
		}

		/**
		 * @return the state
		 */
		public int getState() {
			return state;
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
	 * Finds a PID given its command line prefix as found in /proc/.../cmdline
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
					LogFactory.getLogger(VirtualServerManager.class).log(Level.FINE, null, err);
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
	) throws IOException, SQLException {
		try {
			try {
				try {
					// Find the ID of the server from xm list
					XmList xmList = new XmList(serverName);
					if(!serverName.equals(xmList.getName())) throw new AssertionError("serverName!=xmList.name");
					int domid = xmList.getDomid();

					// Find the PID of its qemu handler from its ID
					// Xen 3.0.3 on CentOS 5:
					int         pid = findPid("/usr/lib64/xen/bin/qemu-dm\u0000-d\u0000"+domid+"\u0000"); // Hardware virtualized
					if(pid==-1) pid = findPid("/usr/lib64/xen/bin/qemu-dm\u0000-M\u0000xenpv\u0000-d\u0000"+domid+"\u0000"); // New Paravirtualized
					if(pid==-1) pid = findPid("/usr/lib64/xen/bin/xen-vncfb\u0000--unused\u0000--listen\u0000127.0.0.1\u0000--domid\u0000"+domid+"\u0000"); // Old Paravirtualized
					// Xen 4.6 on CentOS 7:
					if(pid==-1) pid = findPid("/usr/lib64/xen/bin/qemu-system-i386\u0000-xen-domid\u0000"+domid+"\u0000"); // Paravirtualized
					if(pid==-1) throw new IOException("Unable to find PID");

					// Find its port from lsof given its PID
					PackageManager.installPackage(PackageManager.PackageName.LSOF);
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
					//System.out.println("values.size()="+values.size());
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
					//System.out.println("vncPort="+vncPort);
					if(vncPort==Integer.MIN_VALUE) throw new ParseException("Unexpected output from lsof: "+lsof, 0);

					// Connect to port and tunnel through all data until EOF
					final Socket vncSocket = getVncSocket(vncPort);
					try {
						InputStream vncIn = vncSocket.getInputStream();
						try {
							final OutputStream vncOut = vncSocket.getOutputStream();
							try {
								// socketIn -> vncOut in another thread
								Thread inThread = new Thread(() -> {
									try {
										try {
											byte[] buff = new byte[4096];
											int ret;
											while((ret=socketIn.read(buff, 0, 4096))!=-1) {
												vncOut.write(buff, 0, ret);
												vncOut.flush();
											}
										} finally {
											try {
												vncSocket.close();
											} catch(SocketException e) {
												LogFactory.getLogger(VirtualServerManager.class).log(Level.FINE, null, e);
											}
										}
									} catch(ThreadDeath TD) {
										throw TD;
									} catch(Throwable T) {
										LogFactory.getLogger(VirtualServerManager.class).log(Level.SEVERE, null, T);
									}
								});
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
								try {
									vncOut.close();
								} catch(SocketException e) {
									LogFactory.getLogger(VirtualServerManager.class).log(Level.FINE, null, e);
								}
							}
						} finally {
							try {
								vncIn.close();
							} catch(SocketException e) {
								LogFactory.getLogger(VirtualServerManager.class).log(Level.FINE, null, e);
							}
						}
					} finally {
						closeVncSocket(vncPort, vncSocket);
					}
				} finally {
					try {
						socketIn.close();
					} catch(SocketException e) {
						LogFactory.getLogger(VirtualServerManager.class).log(Level.FINE, null, e);
					}
				}
			} finally {
				try {
					socketOut.close();
				} catch(SocketException e) {
					LogFactory.getLogger(VirtualServerManager.class).log(Level.FINE, null, e);
				}
			}
		} catch(ParseException err) {
			throw new IOException(err);
		} finally {
			try {
				socket.close();
			} catch(SocketException e) {
				LogFactory.getLogger(VirtualServerManager.class).log(Level.FINE, null, e);
			}
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

	public static int getVirtualServerStatus(String virtualServer) throws IOException, SQLException {
		try {
			// Find the ID of the server from xm list
			XmList xmList = new XmList(virtualServer);
			// TODO: This is no longer available in the --long list output on Xen 4.6
			if(!virtualServer.equals(xmList.getName())) throw new AssertionError("virtualServer!=xmList.name");
			return xmList.getState();
		} catch(IOException e) {
			String message = e.getMessage();
			if(message!=null && message.endsWith(" does not exist.")) return VirtualServer.DESTROYED;
			throw e;
		} catch(ParseException err) {
			throw new IOException(err);
		}
	}

	/**
	 * No concurrent update of state file.
	 */
	private static final Object drbdVerifyStateLock = new Object();

	public static long verifyVirtualDisk(String virtualServer, String device) throws IOException {
		synchronized(drbdVerifyStateLock) {
			return Long.parseLong(
				AOServDaemon.execAndCapture(
					"/opt/aoserv-daemon/bin/drbd-verify",
					virtualServer + "-" + device
				).trim()
			);
		}
	}

	public static void updateVirtualDiskLastVerified(String virtualServer, String device, long lastVerified) throws IOException {
		synchronized(drbdVerifyStateLock) {
			AOServDaemon.exec(
				"/opt/aoserv-daemon/bin/set-drbd-last-verified",
				virtualServer + "-" + device,
				Long.toString(lastVerified)
			);
		}
	}
}
