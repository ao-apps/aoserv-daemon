/*
 * Copyright 2004-2013, 2017, 2018, 2019 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.random;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.io.unix.linux.DevRandom;
import com.aoindustries.util.BufferManager;
import com.aoindustries.util.WrappedException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.logging.Level;

/**
 * Watches the amount of entropy available in the system, obtains entropy from the master when running low,
 * provides entropy to the master when has extra.
 *
 * @author  AO Industries, Inc.
 */
public final class RandomEntropyManager implements Runnable {

	/**
	 * The minimum delay between scans.
	 */
	public static final long MIN_DELAY = 100;

	/**
	 * The maximum delay between scans when obtaining from the master.
	 */
	public static final long MAX_OBTAIN_DELAY = 15 * 1000;

	/**
	 * The maximum delay between scans when providing to the master.
	 */
	public static final long MAX_PROVIDE_DELAY = 5 * 60 * 1000;

	/**
	 * The delay after an error occurs.
	 */
	public static final long ERROR_DELAY = 60 * 1000;

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
	 * The number of bits available where will obtain from master server.
	 * <p>
	 * This is scaled from an expected pool size of <code>4096</code>.
	 * See <code>/proc/sys/kernel/random/poolsize</code>
	 * </p>
	 */
	public static final int OBTAIN_THRESHOLD = 2048;

	/**
	 * The number of bits below which haveged will be automatically installed.
	 * This is an attempt to keep the system non-blocking with good entropy.
	 */
	public static final int HAVEGED_THRESHOLD = 1024;

	static {
		assert PROVIDE_THRESHOLD > DESIRED_BITS;
		assert DESIRED_BITS > OBTAIN_THRESHOLD;
		assert OBTAIN_THRESHOLD > HAVEGED_THRESHOLD;
	}

	private static Thread thread;

	private RandomEntropyManager() {
	}

	@Override
	public void run() {
		while(true) {
			try {
				AOServConnector conn = AOServDaemon.getConnector();
				boolean lastObtain = true;
				while(true) {
					long sleepyTime;
					int entropyAvail = DevRandom.getEntropyAvail();
					if(entropyAvail < OBTAIN_THRESHOLD) {
						lastObtain = true;
						int bytesNeeded = (DESIRED_BITS - entropyAvail) / 8;
						if(bytesNeeded > BufferManager.BUFFER_SIZE) bytesNeeded = BufferManager.BUFFER_SIZE;
						byte[] buff = BufferManager.getBytes();
						try {
							int obtained = conn.getMasterEntropy(buff, bytesNeeded);
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
						if(entropyAvail < HAVEGED_THRESHOLD) {
							Server thisServer = AOServDaemon.getThisServer();
							OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
							int osvId = osv.getPkey();
							if(
								// Supported operating systems that will automatically install haveged
								osvId == OperatingSystemVersion.CENTOS_7_X86_64
							) {
								PackageManager.installPackage(
									PackageManager.PackageName.HAVEGED,
									() -> {
										try {
											AOServDaemon.exec("/usr/bin/systemctl", "enable", "haveged.service");
											AOServDaemon.exec("/usr/bin/systemctl", "start",  "haveged.service");
										} catch(IOException e) {
											throw new WrappedException(e);
										}
									}
								);
							} else if(
								// Supported operating systems that will not automatically install haveged
								osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
								&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
								&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
								&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
							) {
								System.out.println("Unsupported OperatingSystemVersion: " + osv);
							}
						}
						if(entropyAvail < OBTAIN_THRESHOLD) {
							// Sleep proportional to the amount of pool needed
							sleepyTime = MIN_DELAY + entropyAvail * (MAX_OBTAIN_DELAY - MIN_DELAY) / DESIRED_BITS;
						} else {
							sleepyTime = MAX_OBTAIN_DELAY;
						}
					} else {
						long masterNeeded = -1;
						if(entropyAvail >= PROVIDE_THRESHOLD) {
							lastObtain = false;
							int provideBytes = (entropyAvail - DESIRED_BITS) / 8;
							masterNeeded = conn.getMasterEntropyNeeded();
							if(provideBytes > masterNeeded) provideBytes = (int)masterNeeded;
							if(provideBytes > 0) {
								if(provideBytes > BufferManager.BUFFER_SIZE) provideBytes = BufferManager.BUFFER_SIZE;
								if(AOServDaemon.DEBUG) System.err.println("DEBUG: RandomEntropyManager: Providing " + provideBytes + " bytes (" + (provideBytes * 8) + " bits) of entropy to master server");
								byte[] buff = BufferManager.getBytes();
								try {
									DevRandom.nextBytesStatic(buff, 0, provideBytes);
									conn.addMasterEntropy(buff, provideBytes);
								} finally {
									BufferManager.release(buff, true);
								}
								masterNeeded -= provideBytes;
							}
						}
						if(lastObtain) {
							sleepyTime = MAX_OBTAIN_DELAY;
						} else {
							// Sleep for the longest delay or shorter if master needs more entropy
							if(masterNeeded == -1) masterNeeded = conn.getMasterEntropyNeeded();
							if(masterNeeded > 0) {
								// Sleep proportional to the amount of master pool needed
								sleepyTime = MAX_PROVIDE_DELAY - masterNeeded * (MAX_PROVIDE_DELAY - MIN_DELAY) / AOServConnector.MASTER_ENTROPY_POOL_SIZE;
							} else {
								sleepyTime = MAX_PROVIDE_DELAY;
							}
						}
					}

					try {
						Thread.sleep(sleepyTime);
					} catch(InterruptedException err) {
						LogFactory.getLogger(this.getClass()).log(Level.WARNING, null, err);
					}
				}
			} catch(ThreadDeath TD) {
				throw TD;
			} catch(Throwable T) {
				LogFactory.getLogger(this.getClass()).log(Level.SEVERE, null, T);
				try {
					Thread.sleep(ERROR_DELAY);
				} catch(InterruptedException err) {
					LogFactory.getLogger(this.getClass()).log(Level.WARNING, null, err);
				}
			}
		}
	}

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
