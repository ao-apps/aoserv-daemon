/*
 * Copyright 2013, 2016 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.net;

import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.validator.InetAddress;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.server.VirtualServerManager;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxProcess;
import com.aoindustries.io.FileUtils;
import com.aoindustries.nio.charset.Charsets;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Manages the null route config file.
 *
 * @author  AO Industries, Inc.
 */
final public class NullRouteManager {

	private static final boolean DEBUG = true;

	/**
	 * The maximum number of milliseconds to wait between status checks.
	 * This is mostly here to avoid possible long waits during system time resets.
	 */
	private static final long MAX_WAIT_TIME = 60L * 1000L; // One minute

	/**
	 * When an IP address is repeatedly null routed, its duration is increased.
	 */
	private static final long[] durations = {
		1L * 60L * 1000L, // 1 minute
		2L * 60L * 1000L, // 2 minutes
		5L * 60L * 1000L, // 5 minutes
		10L * 60L * 1000L, // 10 minutes
	};

	/**
	 * A null route level is reduced only after no null route for this time span.
	 */
	private static final long REDUCE_LEVEL_QUIET_TIME = 60L * 60L * 1000L; // 1 hour

	private static final File BIRD_NULL_CONFIG = new File("/etc/opt/aoserv-daemon/bird-null-auto.conf");
	private static final File BIRD_NULL_CONFIG_NEW = new File("/etc/opt/aoserv-daemon/bird-null-auto.conf.new");

	volatile private static NullRouteManager instance;

	public static void start() throws IOException, SQLException {
		AOServer thisAOServer=AOServDaemon.getThisAOServer();
		int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
		if(
			// Only done for these operating systems
			(
				osv == OperatingSystemVersion.CENTOS_5_DOM0_I686
				|| osv == OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				|| osv == OperatingSystemVersion.CENTOS_7_DOM0_X86_64
			)
			// Check config after OS check so config entry not needed
			&& AOServDaemonConfiguration.isManagerEnabled(NullRouteManager.class)
		) {
			synchronized(System.out) {
				if(instance==null) {
					System.out.print("Starting NullRouteManager: ");
					instance = new NullRouteManager();
					instance.startThread();
					System.out.println("Done");
				}
			}
		}
	}

	static class NullRoute {
		final int ip;
		int level;
		long startTime;
		long endTime;

		NullRoute(int ip, long currentTime) {
			this.ip = ip;
			this.level = 0;
			this.startTime = currentTime;
			this.endTime = currentTime + durations[level];
			if(DEBUG) {
				final PrintStream out = System.out;
				synchronized(out) {
					out.print(NullRouteManager.class.getName());
					out.print(IPAddress.getIPAddressForInt(ip));
					out.print(": new: level=");
					out.println(level);
				}
			}
		}

		/**
		 * Decrement the null route level based on the amount of quiet time
		 */
		void reduceLevel(long currentTime) {
			// Check for system time set to past
			if(currentTime < startTime) {
				// System time reset to the past, start over
				level = -1;
				if(DEBUG) {
					final PrintStream out = System.out;
					synchronized(out) {
						out.print(NullRouteManager.class.getName());
						out.print(": ");
						out.print(IPAddress.getIPAddressForInt(ip));
						out.print(": system time reset: level=");
						out.println(level);
					}
				}
			} else if(currentTime >= endTime) {
				long decrementLevels = (currentTime - endTime) / REDUCE_LEVEL_QUIET_TIME;
				assert decrementLevels >= 0;
				if(decrementLevels != 0) {
					level -= Math.min(decrementLevels, durations.length);
					if(level < -1) level = -1;
					if(DEBUG) {
						final PrintStream out = System.out;
						synchronized(out) {
							out.print(NullRouteManager.class.getName());
							out.print(": ");
							out.print(IPAddress.getIPAddressForInt(ip));
							out.print(": decremented: level=");
							out.println(level);
						}
					}
				}
			}
		}

		/**
		 * Increases the null route level and starts a new null route time period
		 */
		void increaseLevel(long currentTime) {
			level = Math.min(level+1, durations.length-1);
			startTime = currentTime;
			endTime = currentTime + durations[level];
			if(DEBUG) {
				final PrintStream out = System.out;
				synchronized(out) {
					out.print(NullRouteManager.class.getName());
					out.print(": ");
					out.print(IPAddress.getIPAddressForInt(ip));
					out.print(": incremented: level=");
					out.println(level);
				}
			}
		}
	}

	private static final Map<Integer,NullRoute> nullRoutes = new LinkedHashMap<>();

	private static final Object threadLock = new Object();
	private Thread thread;

	private NullRouteManager() {
	}

	private void startThread() {
		synchronized(threadLock) {
			if(thread==null) {
				thread = new Thread(NullRouteManager.class.getName()) {
					@Override
					public void run() {
						while(true) {
							try {
								final StringBuilder newContents = new StringBuilder();
								synchronized(nullRoutes) {
									while(true) {
										// Verify config file while cleaning-up entries
										newContents.setLength(0);
										final long currentTime = System.currentTimeMillis();
										long nearestEnding = Long.MAX_VALUE;
										Iterator<Map.Entry<Integer,NullRoute>> iter = nullRoutes.entrySet().iterator();
										while(iter.hasNext()) {
											Map.Entry<Integer,NullRoute> entry = iter.next();
											NullRoute nullRoute = entry.getValue();
											// If null route currently in progress, add to the output file
											if(currentTime >= nullRoute.startTime && currentTime < nullRoute.endTime) {
												String ipString = IPAddress.getIPAddressForInt(nullRoute.ip);
												InetAddress inetAddress = InetAddress.valueOf(ipString);
												assert inetAddress.isIPv4();
												// Never null-route private IP addresses, such as those used for communication between routers for BGP sessions
												if(!inetAddress.isUniqueLocal()) {
													newContents
														.append("route ")
														.append(ipString)
														.append("/32 drop;\n")
													;
												}
												// Find the null route that expires next
												if(nullRoute.endTime < nearestEnding) nearestEnding = nullRoute.endTime;
											} else {
												nullRoute.reduceLevel(currentTime);
												if(nullRoute.level < 0) {
													// Quiet long enough to remove entirely
													iter.remove();
												}
											}
										}
										byte[] newBytes = newContents.toString().getBytes(Charsets.UTF_8.name()); // .name() only for JDK < 1.6 compatibility
										// See if file has changed
										if(!FileUtils.contentEquals(BIRD_NULL_CONFIG, newBytes)) {
											try (OutputStream out = new FileOutputStream(BIRD_NULL_CONFIG_NEW)) {
												out.write(newBytes);
											}
											FileUtils.rename(BIRD_NULL_CONFIG_NEW, BIRD_NULL_CONFIG);
											// kill -HUP bird if updated
											int pid = VirtualServerManager.findPid("/opt/bird/sbin/bird\u0000-u\u0000bird\u0000-g\u0000bird");
											if(pid == -1) {
												LogFactory.getLogger(NullRouteManager.class).log(Level.SEVERE, "bird not running");
											} else {
												new LinuxProcess(pid).signal("HUP");
											}
										}
										// Wait until more action to take
										if(nearestEnding == Long.MAX_VALUE) {
											// No null routes active, wait indefinitely until notified
											nullRoutes.wait(MAX_WAIT_TIME);
										} else {
											long waitTime = nearestEnding - System.currentTimeMillis();
											if(waitTime > MAX_WAIT_TIME) waitTime = MAX_WAIT_TIME;
											if(waitTime > 0) nullRoutes.wait(waitTime);
										}
									}
								}
							} catch (ThreadDeath TD) {
								throw TD;
							} catch (Throwable T) {
								LogFactory.getLogger(NullRouteManager.class).log(Level.SEVERE, null, T);
							}
							try {
								sleep(1000);
							} catch (InterruptedException err) {
								LogFactory.getLogger(NullRouteManager.class).log(Level.WARNING, null, err);
							}
						}
					}
				};
				thread.setPriority(Thread.MAX_PRIORITY);
				thread.start();
			}
		}
	}

	/**
	 * Adds a new null route to the system.
	 */
	public static void addNullRoute(int nullingIp) {
		final Integer ipObj = nullingIp;
		final long currentTime = System.currentTimeMillis();
		synchronized(nullRoutes) {
			// Look for an existing null route
			NullRoute nullRoute = nullRoutes.get(ipObj);
			if(nullRoute!=null) {
				// If null route currently in progress, ignore request
				if(currentTime >= nullRoute.startTime && currentTime < nullRoute.endTime) {
					// Ignore request
					return;
				}
				nullRoute.reduceLevel(currentTime);
				if(nullRoute.level < 0) {
					// Quiet long enough to start over
					nullRoute = null;
				} else {
					// Increase level
					nullRoute.increaseLevel(currentTime);
				}
			}
			if(nullRoute==null) nullRoutes.put(ipObj, new NullRoute(nullingIp, currentTime));
			nullRoutes.notify();
		}
	}
}
