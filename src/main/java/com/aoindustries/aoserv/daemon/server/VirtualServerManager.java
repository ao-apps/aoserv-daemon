/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2012, 2013, 2014, 2017, 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.server;

import com.aoapps.hodgepodge.io.stream.StreamableInput;
import com.aoapps.hodgepodge.io.stream.StreamableOutput;
import com.aoapps.lang.ProcessResult;
import com.aoapps.lang.Strings;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.infrastructure.VirtualServer;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
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
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The <code>ServerManager</code> controls stuff at a server level.
 *
 * @author  AO Industries, Inc.
 */
public final class VirtualServerManager {

	/** Make no instances. */
	private VirtualServerManager() {throw new AssertionError();}

	private static final Logger logger = Logger.getLogger(VirtualServerManager.class.getName());

	/**
	 * Gets the xm/xl command used for this server.
	 */
	public static String getXmCommand() throws IOException, SQLException {
		OperatingSystemVersion osv = AOServDaemon.getThisServer().getHost().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		if(
			osvId == OperatingSystemVersion.CENTOS_5_DOM0_I686
			|| osvId == OperatingSystemVersion.CENTOS_5_DOM0_X86_64
		) {
			return "/usr/sbin/xm";
		} else if(osvId == OperatingSystemVersion.CENTOS_7_DOM0_X86_64) {
			return "/sbin/xl";
		} else {
			throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
		}
	}

	public static class XmList {

		private final int domid;
		private final String uuid;
		private final int vcpus;
		private final double cpuWeight;
		private final long memory;
		private final long shadowMemory;
		private final long maxmem;
		private final String name;
		private final String onReboot;

		XmList(String serverName) throws ParseException, IOException, SQLException {
			OperatingSystemVersion osv = AOServDaemon.getThisServer().getHost().getOperatingSystemVersion();
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
				cpuWeight = domainNode.getDouble("cpu_weight");
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
				// state is skipped
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
				//for(Map.Entry<String, Object> entry : domainNode.entrySet()) {
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
				cpuWeight = (Double)schedParamsNode.get("weight");
				memory = ((Double)bInfoNode.get("target_memkb")).longValue();
				shadowMemory = ((Double)bInfoNode.get("shadow_memkb")).longValue();
				maxmem = ((Double)bInfoNode.get("max_memkb")).longValue();
				name = (String)cInfoNode.get("name");
				onReboot = (String)configNode.get("on_reboot");
			} else {
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
			}
		}

		/**
		 * @return the cpuWeight
		 */
		public double getCpuWeight() {
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
		if(list != null) {
			for(String filename : list) {
				try {
					boolean allDigits = true;
					for(int i = 0, len = filename.length(); i < len; i++) {
						char ch = filename.charAt(i);
						if(ch < '0' || ch > '9') {
							allDigits = false;
							break;
						}
					}
					// Is a PID directory
					if(allDigits) {
						File dir = new File(procFile, filename);
						if(dir.isDirectory()) {
							File cmdlineFile = new File(dir, "cmdline");
							if(cmdlineFile.exists()) {
								int pos = 0;
								int prefixLen = cmdlinePrefix.length();
								try (InputStream in = new BufferedInputStream(new FileInputStream(cmdlineFile), cmdlinePrefix.length())) {
									int b;
									while(pos < prefixLen && (b = in.read()) != -1) {
										if((char)b != cmdlinePrefix.charAt(pos)) {
											break;
										}
										pos++;
									}
									if(pos == prefixLen) return Integer.parseInt(filename);
								}
							}
						}
					}
				} catch(IOException err) {
					// Log as warning
					logger.log(Level.FINE, null, err);
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
		final StreamableInput socketIn,
		StreamableOutput socketOut,
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
					// Xen 4.6 on CentOS 7:
					int         pid = findPid("/usr/lib64/xen/bin/qemu-system-i386\u0000-xen-domid\u0000"+domid+"\u0000"); // Paravirtualized
					// Xen 3.0.3 on CentOS 5:
					if(pid==-1) pid = findPid("/usr/lib64/xen/bin/qemu-dm\u0000-d\u0000"+domid+"\u0000"); // Hardware virtualized
					if(pid==-1) pid = findPid("/usr/lib64/xen/bin/qemu-dm\u0000-M\u0000xenpv\u0000-d\u0000"+domid+"\u0000"); // New Paravirtualized
					if(pid==-1) pid = findPid("/usr/lib64/xen/bin/xen-vncfb\u0000--unused\u0000--listen\u0000127.0.0.1\u0000--domid\u0000"+domid+"\u0000"); // Old Paravirtualized
					if(pid==-1) throw new IOException("Unable to find PID for " + serverName + " (id " + domid + ")");

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
					List<String> values = Strings.split(lsof, '\u0000');
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
								@SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
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
												logger.log(Level.FINE, null, e);
											}
										}
									} catch(ThreadDeath td) {
										throw td;
									} catch(Throwable t) {
										logger.log(Level.SEVERE, null, t);
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
									//    // Let the in thread complete its work before closing streams
									//    inThread.join();
									//} catch(InterruptedException err) {
									//    // Restore the interrupted status
									//    Thread.currentThread().interrupt();
									//    InterruptedIOException ioErr = new InterruptedIOException();
									//    ioErr.initCause(err);
									//    throw ioErr;
									//}
								//}
							} finally {
								try {
									vncOut.close();
								} catch(SocketException e) {
									logger.log(Level.FINE, null, e);
								}
							}
						} finally {
							try {
								vncIn.close();
							} catch(SocketException e) {
								logger.log(Level.FINE, null, e);
							}
						}
					} finally {
						closeVncSocket(vncPort, vncSocket);
					}
				} finally {
					try {
						socketIn.close();
					} catch(SocketException e) {
						logger.log(Level.FINE, null, e);
					}
				}
			} finally {
				try {
					socketOut.close();
				} catch(SocketException e) {
					logger.log(Level.FINE, null, e);
				}
			}
		} catch(ParseException err) {
			throw new IOException(err);
		} finally {
			try {
				socket.close();
			} catch(SocketException e) {
				logger.log(Level.FINE, null, e);
			}
		}
	}

	/**
	 * Tracks the open connections to close them when new connections are established.
	 */
	private static final Map<Integer, Socket> openVncSockets = new HashMap<>();

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
					logger.log(Level.INFO, null, err);
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
				logger.log(Level.INFO, null, err);
			}
			if(openVncSockets.get(vncPort)==vncSocket) openVncSockets.remove(vncPort);
		}
	}

	public static String createVirtualServer(String virtualServer) throws IOException, SQLException {
		ProcessResult result = ProcessResult.exec(
			new String[] {
				getXmCommand(),
				"create",
				// Now using auto directory to avoid starting wrong place: "/etc/xen/guests/"+virtualServer+"/config"
				"/etc/xen/auto/" + virtualServer
			}
		);
		String stderr = result.getStderr();
		if(result.getExitVal()==0) {
			// Log any errors
			if(stderr.length()>0) logger.fine(stderr);
			return result.getStdout();
		} else {
			throw new IOException(stderr);
		}
	}

	public static String rebootVirtualServer(String virtualServer) throws IOException, SQLException {
		ProcessResult result = ProcessResult.exec(
			new String[] {
				getXmCommand(),
				"reboot",
				virtualServer
			}
		);
		String stderr = result.getStderr();
		if(result.getExitVal()==0) {
			// Log any errors
			if(stderr.length()>0) logger.fine(stderr);
			return result.getStdout();
		} else {
			throw new IOException(stderr);
		}
	}

	public static String shutdownVirtualServer(String virtualServer) throws IOException, SQLException {
		ProcessResult result = ProcessResult.exec(
			new String[] {
				getXmCommand(),
				"shutdown",
				virtualServer
			}
		);
		String stderr = result.getStderr();
		if(result.getExitVal()==0) {
			// Log any errors
			if(stderr.length()>0) logger.fine(stderr);
			return result.getStdout();
		} else {
			throw new IOException(stderr);
		}
	}

	public static String destroyVirtualServer(String virtualServer) throws IOException, SQLException {
		ProcessResult result = ProcessResult.exec(
			new String[] {
				getXmCommand(),
				"destroy",
				virtualServer
			}
		);
		String stderr = result.getStderr();
		if(result.getExitVal()==0) {
			// Log any errors
			if(stderr.length()>0) logger.fine(stderr);
			return result.getStdout();
		} else {
			throw new IOException(stderr);
		}
	}

	public static String pauseVirtualServer(String virtualServer) throws IOException, SQLException {
		ProcessResult result = ProcessResult.exec(
			new String[] {
				getXmCommand(),
				"pause",
				virtualServer
			}
		);
		String stderr = result.getStderr();
		if(result.getExitVal()==0) {
			// Log any errors
			if(stderr.length()>0) logger.fine(stderr);
			return result.getStdout();
		} else {
			throw new IOException(stderr);
		}
	}

	public static String unpauseVirtualServer(String virtualServer) throws IOException, SQLException {
		ProcessResult result = ProcessResult.exec(
			new String[] {
				getXmCommand(),
				"unpause",
				virtualServer
			}
		);
		String stderr = result.getStderr();
		if(result.getExitVal()==0) {
			// Log any errors
			if(stderr.length()>0) logger.fine(stderr);
			return result.getStdout();
		} else {
			throw new IOException(stderr);
		}
	}

	private static final Pattern xmListStatusPattern = Pattern.compile("^\\S+\\s+[0-9]+\\s+[0-9]+\\s+[0-9]+\\s+(\\S+)\\s+\\S+$");

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

	public static int getVirtualServerStatus(String virtualServer) throws IOException, SQLException {
		try {
			List<String> lines = Strings.splitLines(
				AOServDaemon.execAndCapture(
					getXmCommand(),
					"list",
					virtualServer
				)
			);
			if(lines.size() != 2) throw new IOException("Expected two lines, got " + lines.size() + ": " + lines);
			String header = lines.get(0);
			if(!header.startsWith("Name ")) throw new IOException("Header doesn't start with \"Name \": " + header);
			String status = lines.get(1);
			Matcher matcher = xmListStatusPattern.matcher(status);
			if(!matcher.find()) throw new IOException("Status line doesn't match expected pattern: " + status);
			return parseState(matcher.group(1));
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
