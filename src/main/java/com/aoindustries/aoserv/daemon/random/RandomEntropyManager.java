/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2004-2013, 2017, 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.random;

import com.aoapps.io.posix.linux.DevRandom;
import com.aoapps.lang.util.BufferManager;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Watches the amount of entropy available in the system, obtains entropy from the master when running low,
 * provides entropy to the master when has extra.
 *
 * @author  AO Industries, Inc.
 */
public final class RandomEntropyManager implements Runnable {

	private static final Logger logger = Logger.getLogger(RandomEntropyManager.class.getName());

	/**
	 * The minimum delay between scans.
	 */
	public static final long MIN_DELAY = 100;

	/**
	 * The maximum delay between scans when obtaining from the master.
	 */
	public static final long MAX_OBTAIN_DELAY = 5L * 1000;

	/**
	 * The delay when obtaining from the master is incomplete (master out of entropy).
	 * This is used to avoid hitting the master stupid-hard when it is depleted.
	 */
	public static final long MAX_OBTAIN_INCOMPLETE_DELAY = 60L * 1000;

	/**
	 * The maximum delay between scans when at the desired entropy.
	 */
	public static final long MAX_DESIRED_DELAY = 15L * 1000;

	/**
	 * The minimum interval between calls to {@code getMasterEntropyNeeded()}
	 */
	public static final long GET_MASTER_ENTROPY_NEEDED_INTERVAL = 5L * 60 * 1000;

	/**
	 * The delay after an error occurs.
	 */
	public static final long ERROR_DELAY = 60L * 1000;

	/**
	 * The number of bits available where will provide to master server.
	 * <p>
	 * This is scaled from an expected pool size of <code>4096</code>.
	 * See <code>/proc/sys/kernel/random/poolsize</code>
	 * </p>
	 */
	public static final int PROVIDE_THRESHOLD = 3584;

	/**
	 * The number of bits available after providing to the master server.
	 * <p>
	 * This is scaled from an expected pool size of <code>4096</code>.
	 * See <code>/proc/sys/kernel/random/poolsize</code>
	 * </p>
	 */
	public static final int DESIRED_BITS = 3072;

	/**
	 * The number of bits available where will obtain from master server when haveged is not supported or is not currently installed.
	 * <p>
	 * This is scaled from an expected pool size of <code>4096</code>.
	 * See <code>/proc/sys/kernel/random/poolsize</code>
	 * </p>
	 */
	public static final int OBTAIN_THRESHOLD_NO_HAVEGED = 2048;

	/**
	 * The number of bits below which haveged will be automatically installed.
	 * This is an attempt to keep the system non-blocking with good entropy.
	 * <p>
	 * This matches the default number of bits where haveged kicks-in (<code>-w 1024</code>).
	 * </p>
	 */
	public static final int HAVEGED_THRESHOLD = 1024;

	/**
	 * The number of bits available where will obtain from master server when haveged is supported and installed.
	 * We try to let haveged take care of things before pulling from the master.
	 * <p>
	 * This is scaled from an expected pool size of <code>4096</code>.
	 * See <code>/proc/sys/kernel/random/poolsize</code>
	 * </p>
	 */
	public static final int OBTAIN_THRESHOLD_WITH_HAVEGED = 512;

	static {
		assert PROVIDE_THRESHOLD > DESIRED_BITS;
		assert DESIRED_BITS > OBTAIN_THRESHOLD_NO_HAVEGED;
		assert OBTAIN_THRESHOLD_NO_HAVEGED > HAVEGED_THRESHOLD;
		assert HAVEGED_THRESHOLD > OBTAIN_THRESHOLD_WITH_HAVEGED;
	}

	private static Thread thread;

	private RandomEntropyManager() {
		// Do nothing
	}

	@Override
	public void run() {
		while(!Thread.currentThread().isInterrupted()) {
			try {
				AOServConnector conn = AOServDaemon.getConnector();
				Server thisServer = AOServDaemon.getThisServer();
				OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
				int osvId = osv.getPkey();

				boolean havegedSupported;
				boolean havegedInstalled;
				if(
					// Supported operating systems that will automatically install haveged
					osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					havegedSupported = true;
					havegedInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.HAVEGED) != null;
				} else if(
					// Supported operating systems that will not automatically install haveged
					osvId == OperatingSystemVersion.CENTOS_5_DOM0_I686
					|| osvId == OperatingSystemVersion.CENTOS_5_DOM0_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				) {
					havegedSupported = false;
					havegedInstalled = false;
				} else {
					throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
				}
				long obtainThreshold = havegedInstalled ? OBTAIN_THRESHOLD_WITH_HAVEGED : OBTAIN_THRESHOLD_NO_HAVEGED;

				long masterNeeded = Long.MIN_VALUE;
				long masterLastCheckedTime = Long.MIN_VALUE;
				boolean lastObtain = true;
				while(!Thread.currentThread().isInterrupted()) {
					long sleepyTime;
					int entropyAvail = DevRandom.getEntropyAvail();
					if(entropyAvail < obtainThreshold) {
						lastObtain = true;
						int bytesNeeded = (DESIRED_BITS - entropyAvail) / 8;
						if(bytesNeeded > BufferManager.BUFFER_SIZE) bytesNeeded = BufferManager.BUFFER_SIZE;
						boolean obtainedComplete;
						byte[] buff = BufferManager.getBytes();
						try {
							int obtained = conn.getMasterEntropy(buff, bytesNeeded);
							obtainedComplete = obtained == bytesNeeded;
							if(obtained > 0) {
								if(obtained == BufferManager.BUFFER_SIZE) {
									DevRandom.addEntropy(buff);
								} else {
									byte[] newBuff = new byte[obtained];
									System.arraycopy(buff, 0, newBuff, 0, obtained);
									DevRandom.addEntropy(newBuff);
								}
								entropyAvail += obtained * 8;
							}
						} finally {
							BufferManager.release(buff, true);
						}
						if(havegedSupported && entropyAvail < HAVEGED_THRESHOLD) {
							PackageManager.installPackage(
								PackageManager.PackageName.HAVEGED,
								() -> {
									try {
										AOServDaemon.exec("/usr/bin/systemctl", "enable", "haveged.service");
										AOServDaemon.exec("/usr/bin/systemctl", "start",  "haveged.service");
									} catch(IOException e) {
										throw new UncheckedIOException(e);
									}
								}
							);
							havegedInstalled = true;
							obtainThreshold = OBTAIN_THRESHOLD_WITH_HAVEGED;
						}
						if(!obtainedComplete) {
							sleepyTime = MAX_OBTAIN_INCOMPLETE_DELAY;
						} else if(entropyAvail < obtainThreshold) {
							// Sleep proportional to the amount of pool needed
							sleepyTime = MIN_DELAY + entropyAvail * (MAX_OBTAIN_DELAY - MIN_DELAY) / DESIRED_BITS;
						} else {
							sleepyTime = MAX_OBTAIN_DELAY;
						}
					} else {
						// Sleep longer once desired bits built-up
						if(entropyAvail >= DESIRED_BITS) {
							lastObtain = false;
						}
						if(entropyAvail >= PROVIDE_THRESHOLD) {
							int provideBytes = (entropyAvail - DESIRED_BITS) / 8;
							long currentTime = System.currentTimeMillis();
							if(
								masterNeeded == Long.MIN_VALUE
								|| Math.abs(currentTime - masterLastCheckedTime) >= GET_MASTER_ENTROPY_NEEDED_INTERVAL
							) {
								masterNeeded = conn.getMasterEntropyNeeded();
								masterLastCheckedTime = currentTime;
							}
							if(provideBytes > masterNeeded) provideBytes = (int)masterNeeded;
							if(provideBytes > 0) {
								if(provideBytes > BufferManager.BUFFER_SIZE) provideBytes = BufferManager.BUFFER_SIZE;
								if(AOServDaemon.DEBUG) System.err.println("DEBUG: RandomEntropyManager: Providing " + provideBytes + " bytes (" + (provideBytes * 8) + " bits) of entropy to master server");
								byte[] buff = BufferManager.getBytes();
								try {
									DevRandom.nextBytesStatic(buff, 0, provideBytes);
									masterNeeded = conn.addMasterEntropy(buff, provideBytes);
									masterLastCheckedTime = currentTime;
								} finally {
									BufferManager.release(buff, true);
								}
							}
						}
						sleepyTime = lastObtain ? MAX_OBTAIN_DELAY : MAX_DESIRED_DELAY;
					}

					try {
						Thread.sleep(sleepyTime);
					} catch(InterruptedException err) {
						logger.log(Level.WARNING, null, err);
						// Restore the interrupted status
						Thread.currentThread().interrupt();
					}
				}
			} catch(ThreadDeath td) {
				throw td;
			} catch(Throwable t) {
				logger.log(Level.SEVERE, null, t);
				try {
					Thread.sleep(ERROR_DELAY);
				} catch(InterruptedException err) {
					logger.log(Level.WARNING, null, err);
					// Restore the interrupted status
					Thread.currentThread().interrupt();
				}
			}
		}
	}

	@SuppressWarnings("UseOfSystemOutOrSystemErr")
	public static void start() throws IOException, SQLException {
		Server thisServer = AOServDaemon.getThisServer();
		OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
		int osvId = osv.getPkey();

		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				// All operating systems currently sharing entropy
				// Check config after OS check so config entry not needed
				AOServDaemonConfiguration.isManagerEnabled(RandomEntropyManager.class)
				&& thread == null
			) {
				System.out.print("Starting RandomEntropyManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.CENTOS_5_DOM0_I686
					|| osvId == OperatingSystemVersion.CENTOS_5_DOM0_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_DOM0_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					// Avoid random manager when in failover mode
					if(AOServDaemon.getThisServer().getFailoverServer() == null) {
						thread = new Thread(new RandomEntropyManager());
						thread.start();
						System.out.println("Done");
					} else {
						System.out.println("Disabled: Running in failover mode");
					}
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}
}
