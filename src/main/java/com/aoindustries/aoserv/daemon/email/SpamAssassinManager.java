/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2005-2013, 2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.email;

import com.aoapps.collections.AoCollections;
import com.aoapps.cron.CronDaemon;
import com.aoapps.cron.CronJob;
import com.aoapps.cron.Schedule;
import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.io.posix.Stat;
import com.aoapps.lang.io.FileUtils;
import com.aoapps.lang.validation.ValidationException;
import com.aoapps.net.InetAddress;
import com.aoapps.net.Port;
import com.aoapps.tempfiles.TempFile;
import com.aoapps.tempfiles.TempFileContext;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.email.SpamAssassinMode;
import com.aoindustries.aoserv.client.linux.Group;
import com.aoindustries.aoserv.client.linux.GroupServer;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.net.AppProtocol;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.net.Host;
import com.aoindustries.aoserv.client.net.IpAddress;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.server.ServerManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ProtocolFamily;
import java.net.StandardProtocolFamily;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The primary purpose of the manager is to decouple the IMAP server from the SpamAssassin training.
 * The training process is slow and makes the IMAP server hesitate in ways that interfere with the
 * mail client and user.
 * <p>
 * The SpamAssassin manager looks for emails left in specific directories by the IMAP server.
 * When these files are found, the SpamAssassin training command (<code>sa-learn</code>) is invoked
 * on these files.  The files start with either <code>ham_</code> or <code>spam_</code> depending on
 * the type of messages.  In order to maintain the expected behavior, the files
 * are processed in chronological order.  That way if a user drags a message to the Junk
 * folder then back to the INBOX, it will not be considered spam.
 * </p>
 * <p>
 * To help avoid any race conditions, only files that are at least 1 minute old (or in the future by 1 or more minutes
 * to handle clock changes) are considered on each pass.  This gives the IMAP server at least one minute to write all of
 * its files.
 * </p>
 * <p>
 * Multiple files from one user are sent to <code>sa-learn</code> at once when possible for efficiency.
 * </p>
 * <p>
 * TODO: SELinux port management for non-standard (other than 783) ports.
 * </p>
 * <p>
 * TODO: Somehow report when users drag from Junk to elsewhere (ham training), perhaps we can tweak our sa_discard_score some
 * </p>
 *
 * @author  AO Industries, Inc.
 */
public final class SpamAssassinManager extends BuilderThread implements Runnable {

	private static final Logger logger = Logger.getLogger(SpamAssassinManager.class.getName());

	/**
	 * The interval to sleep after each pass.
	 */
	private static final long DELAY_INTERVAL = 60L * 1000;

	/**
	 * The maximum number of messages that will be sent to <code>sa-learn</code> as a single command.
	 * This is kept fairly low so the <code>sa-learn --sync</code> doesn't take too long and hold
	 * locks that could prevent new mail from arriving.  Between executions, new
	 * mail would have a chance to be scanned.
	 */
	private static final int MAX_SALEARN_BATCH = 100;

	/**
	 * The minimum number of messages being sent to sa-learn that will use a two step
	 * <code>sa-learn --no-sync</code> then <code>sa-learn --sync</code>.
	 * <p>
	 * The choice of 5 here is arbitrary, we have not measured the performance of this versus other values.
	 * This needs to balance the overhead of the additional exec versus the overhead of the sync.
	 * This balance may consider that CPU is generally more plentiful than disk I/O.
	 * </p>
	 */
	private static final int SALEARN_NOSYNC_THRESHOLD = 5;

	/**
	 * The minimum number of children processes.
	 */
	private static final int MIN_CHILDREN = 5;

	/**
	 * The maximum number of children processes.
	 */
	private static final int MAX_CHILDREN = 25;

	/**
	 * The number of bytes of RAM reserved per child process.  This is used to scale the maximum number of children
	 * based on total system memory (as obtained by "MemTotal" in <code>/proc/meminfo</code>).  The result is then
	 * bounded within {@link #MIN_CHILDREN} and {@link #MAX_CHILDREN}.
	 * <p>
	 * We're seeing between 50 MiB and 90 MiB per child in 64-bit CentOS 7, so we're going with 100 MiB per-child.  The
	 * servers certainly have other processes as well, so this should be sufficient to avoid spamd causing swap
	 * thrashing.
	 * </p>
	 */
	private static final long PER_CHILD_MEMORY = 100L * 1024 * 1024; // 100 MiB

	static final int DEFAULT_SPAMD_PORT = 783;

	/**
	 * The directory containing the spam and ham directories.
	 */
	private static final PosixFile incomingDirectory = new PosixFile("/var/spool/aoserv/spamassassin");

	private static SpamAssassinManager spamAssassinManager;

	private static final PosixFile
		configPosixFile = new PosixFile("/etc/sysconfig/spamassassin"),
		localCfPosixFile = new PosixFile("/etc/mail/spamassassin/local.cf")
	;

	private static final File subsysLockFile = new File("/var/lock/subsys/spamassassin");

	private static final PosixFile spamassassinRcFile = new PosixFile("/etc/rc.d/rc3.d/S78spamassassin");

	/**
	 * The expected file permissions for IMAP Spool.
	 */
	private static final long IMAP_SPOOL_MODE = 0644;

	private SpamAssassinManager() {
		// Do nothing
	}

	@Override
	@SuppressWarnings({"SleepWhileInLoop", "UseSpecificCatch", "TooBroadCatch"})
	public void run() {
		long lastStartTime = -1;
		while(!Thread.currentThread().isInterrupted()) {
			try {
				long delay;
				if(lastStartTime == -1) delay = DELAY_INTERVAL;
				else {
					delay = lastStartTime + DELAY_INTERVAL - System.currentTimeMillis();
					if(delay > DELAY_INTERVAL) delay = DELAY_INTERVAL;
				}
				if(delay > 0) {
					try {
						Thread.sleep(DELAY_INTERVAL);
					} catch(InterruptedException err) {
						logger.log(Level.WARNING, null, err);
						// Restore the interrupted status
						Thread.currentThread().interrupt();
						break;
					}
				}
				lastStartTime = System.currentTimeMillis();

				// Process incoming messages
				processIncomingMessages();
			} catch(ThreadDeath td) {
				throw td;
			} catch(Throwable t) {
				logger.log(Level.SEVERE, null, t);
				try {
					Thread.sleep(60000);
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
				osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(SpamAssassinManager.class)
				&& spamAssassinManager == null
			) {
				System.out.print("Starting SpamAssassinManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					spamAssassinManager = new SpamAssassinManager();
					conn.getLinux().getUserServer().addTableListener(spamAssassinManager, 0);
					conn.getNet().getIpAddress().addTableListener(spamAssassinManager, 0);
					PackageManager.addPackageListener(spamAssassinManager);
					new Thread(spamAssassinManager, "SpamAssassinManager").start();
					// Once per day, the razor logs will be trimmed to only include the last 1000 lines
					CronDaemon.addCronJob(new RazorLogTrimmer(), logger);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	/**
	 * The entire filename must be acceptable characters in <code>[a-zA-Z0-9_-]</code>.
	 * This is, along with the character restrictions for usernames, the key aspect
	 * of the security of the following suexec call.
	 */
	private static boolean isFilenameOk(String filename) {
		// Don't allow null
		if(filename == null) return false;

		// Don't allow empty string
		if(filename.isEmpty()) return false;

		for(int d = 0; d < filename.length(); d++) {
			char ch = filename.charAt(d);
			if(
				(ch < 'a' || ch > 'z')
				&& (ch < 'A' || ch > 'Z')
				&& (ch < '0' || ch > '9')
				&& ch != '-'
				&& ch != '_'
			) {
				return false;
			}
		}
		return true;
	}

	private static synchronized void processIncomingMessages() throws IOException, SQLException {
		try {
			// Only process incoming messages when the incoming directory exists
			if(incomingDirectory.getStat().exists()) {
				Server thisServer = AOServDaemon.getThisServer();
				int mailGid;
				{
					GroupServer mail = thisServer.getLinuxServerGroup(Group.MAIL);
					if(mail == null) throw new SQLException("Unable to find GroupServer: " + User.MAIL + " on " + thisServer);
					mailGid = mail.getGid().getId();
				}

				// Used on inner loop
				List<File> deleteFileList = new ArrayList<>();
				StringBuilder tempSB = new StringBuilder();
				List<PosixFile> thisPass = new ArrayList<>(MAX_SALEARN_BATCH);

				while(!Thread.currentThread().isInterrupted()) {
					// End loop if no subdirectories
					String[] incomingDirectoryList = incomingDirectory.list();
					if(incomingDirectoryList == null || incomingDirectoryList.length == 0) break;

					// Find the username that has the oldest timestamp that is also at least one minute old or one minute in the future
					UserServer oldestLsa = null;
					Map<PosixFile, Long> oldestReadyMap = null;
					long oldestTimestamp = -1;

					// The files will be backed-up before being deleted
					deleteFileList.clear();
					long currentTime = System.currentTimeMillis();

					for(String incomingDirectoryFilename : incomingDirectoryList) {
						PosixFile userDirectoryUf = new PosixFile(incomingDirectory, incomingDirectoryFilename, false);
						File userDirectoryFile = userDirectoryUf.getFile();

						// Each filename should be a username
						UserServer lsa = thisServer.getLinuxServerAccount(User.Name.valueOf(incomingDirectoryFilename));
						if(lsa == null) {
							// user not found, backup and then remove
							logger.log(Level.WARNING, "incomingDirectoryFilename = " + incomingDirectoryFilename, new IOException("User not found, deleting"));
							deleteFileList.add(userDirectoryFile);
						} else if(!lsa.getLinuxAccount().getType().isEmail()) {
							// user not email type, backup and then remove
							logger.log(Level.WARNING, "incomingDirectoryFilename = " + incomingDirectoryFilename, new IOException("User not email type, deleting"));
							deleteFileList.add(userDirectoryFile);
						} else if(!lsa.getHome().toString().startsWith("/home/")) {
							// user doesn't have home directory in /home/, backup and then remove
							logger.log(Level.WARNING, "incomingDirectoryFilename = " + incomingDirectoryFilename, new IOException("User home not in /home/, deleting"));
							deleteFileList.add(userDirectoryFile);
						} else {
							// Check permissions and ownership
							{
								// Set permissions, should be 0770
								Stat userDirectoryUfStat = userDirectoryUf.getStat();
								if(userDirectoryUfStat.getMode() != 0770) {
									userDirectoryUf.setMode(0770);
									userDirectoryUfStat = userDirectoryUf.getStat();
								}
								// Set ownership, should by username and group mail
								int lsaUid = lsa.getUid().getId();
								if(userDirectoryUfStat.getUid() != lsaUid || userDirectoryUfStat.getGid() != mailGid) {
									userDirectoryUf.chown(lsaUid, mailGid);
									// Stat not required since not used: userDirectoryUfStat = userDirectoryUf.getStat();
								}
							}
							// Check each filename, searching if this lsa has the oldest timestamp (older or newer than one minute)
							String[] userDirectoryList = userDirectoryUf.list();
							if(userDirectoryList != null && userDirectoryList.length > 0) {
								Map<PosixFile, Long> readyMap = AoCollections.newHashMap(userDirectoryList.length);
								for(String userFilename : userDirectoryList) {
									PosixFile userUf = new PosixFile(userDirectoryUf, userFilename, false);
									File userFile = userUf.getFile();
									if(userFilename.startsWith("ham_") || userFilename.startsWith("spam_")) {
										// Must be a regular file
										Stat userUfStat = userUf.getStat();
										if(userUfStat.isRegularFile()) {
											int pos1 = userFilename.indexOf('_');
											if(pos1 == -1) throw new AssertionError("pos1 == -1"); // This should not happen because of check against ham_ or spam_ above.
											int pos2 = userFilename.indexOf('_', pos1 + 1);
											if(pos2 != -1) {
												try {
													long timestamp = Long.parseLong(userFilename.substring(pos1 + 1, pos2)) * 1000;
													if(
														(timestamp - currentTime) > 60000
														|| (currentTime - timestamp) > 60000
													) {
														if(isFilenameOk(userFilename)) {
															// We're getting files with permissions 0600 still, not sure why.  Fix permissions so accessible
															long mode = userUfStat.getMode();
															if(mode != IMAP_SPOOL_MODE) {
																logger.log(
																	Level.WARNING,
																	"Fixing permissions for \"" + userUf.getPath() + "\": "
																	+ PosixFile.getModeString(mode)
																	+ " → "
																	+ PosixFile.getModeString(IMAP_SPOOL_MODE)
																);
																userUf.setMode(IMAP_SPOOL_MODE);
															}
															readyMap.put(userUf, timestamp);

															// Is the oldest?
															if(oldestLsa == null || timestamp < oldestTimestamp) {
																oldestLsa = lsa;
																oldestReadyMap = readyMap;
																oldestTimestamp = timestamp;
															}
														} else {
															logger.log(Level.WARNING, "userDirectoryUf = " + userDirectoryUf.getPath() + ", userFilename = " + userFilename, new IOException("Invalid character in filename, deleting"));
															deleteFileList.add(userFile);
														}
													}
												} catch(NumberFormatException err) {
													IOException ioErr = new IOException("Unable to find parse timestamp in filename, deleting");
													ioErr.initCause(err);
													logger.log(Level.WARNING, "userDirectoryUf = " + userDirectoryUf.getPath() + ", userFilename = " + userFilename, ioErr);
													deleteFileList.add(userFile);
												}
											} else {
												logger.log(Level.WARNING, "userDirectoryUf = " + userDirectoryUf.getPath() + ", userFilename = " + userFilename, new IOException("Unable to find second underscore (_) in filename, deleting"));
												deleteFileList.add(userFile);
											}
										} else {
											logger.log(Level.WARNING, "userDirectoryUf = " + userDirectoryUf.getPath() + ", userFilename = " + userFilename, new IOException("Not a regular file, deleting"));
											deleteFileList.add(userFile);
										}
									} else {
										logger.log(Level.WARNING, "userDirectoryUf = " + userDirectoryUf.getPath() + ", userFilename = " + userFilename, new IOException("Unexpected filename, should start with \"spam_\" or \"ham_\", deleting"));
										deleteFileList.add(userFile);
									}
								}
							}
						}
					}

					// Back-up and delete the files scheduled for removal.
					BackupManager.backupAndDeleteFiles(deleteFileList);

					// Nothing to do, end loop to sleep
					if(oldestLsa == null) break;
					assert oldestReadyMap != null;

					// Sort the list by oldest time first
					final Map<PosixFile, Long> readyMap = oldestReadyMap;
					List<PosixFile> readyList = new ArrayList<>(oldestReadyMap.keySet());
					Collections.sort(
						readyList,
						(PosixFile uf1, PosixFile uf2) -> readyMap.get(uf1).compareTo(readyMap.get(uf2))
					);

					// Process the oldest file while batching as many spam or ham directories together as possible
					thisPass.clear();
					PosixFile firstUf = readyList.get(0);
					boolean firstIsHam = firstUf.getFile().getName().startsWith("ham_");
					thisPass.add(firstUf);
					for(int c = 1; c < readyList.size(); c++) {
						PosixFile other = readyList.get(c);
						boolean otherIsHam = other.getFile().getName().startsWith("ham_");
						if(firstIsHam == otherIsHam) {
							// If both spam or both ham, batch to one call and remove from later processing
							thisPass.add(other);
							// Only train maximum MAX_SALEARN_BATCH messages at a time
							if(thisPass.size() >= MAX_SALEARN_BATCH) break;
						} else {
							// Mode for that user switched, termination batching loop
							break;
						}
					}

					// Only train SpamAssassin when integration mode not set to none
					SpamAssassinMode integrationMode = oldestLsa.getEmailSpamAssassinIntegrationMode();
					if(!integrationMode.getName().equals(SpamAssassinMode.NONE)) {
						// Make sure sa-learn is installed
						PackageManager.installPackage(PackageManager.PackageName.SPAMASSASSIN);
						// Call sa-learn for this pass
						User.Name username = oldestLsa.getLinuxAccount_username_id();
						tempSB.setLength(0);
						tempSB.append("/usr/bin/sa-learn");
						boolean isNoSync = thisPass.size() >= SALEARN_NOSYNC_THRESHOLD;
						if(isNoSync) tempSB.append(" --no-sync");
						tempSB.append(firstIsHam ? " --ham" : " --spam");
						for(PosixFile uf : thisPass) {
							tempSB.append(' ').append(uf.getPath());
						}
						String command = tempSB.toString();
						//System.err.println("DEBUG: "+SpamAssassinManager.class.getName()+": processIncomingMessagesCentOs: username="+username+" and command=\""+command+"\"");
						try {
							AOServDaemon.suexec(
								username,
								new File(oldestLsa.getHome().toString()),
								command,
								15
							);
						} finally {
							if(isNoSync) {
								String command2 = "/usr/bin/sa-learn --sync";
								//System.err.println("DEBUG: "+SpamAssassinManager.class.getName()+": processIncomingMessagesCentOs: username="+username+" and command2=\""+command2+"\"");
								AOServDaemon.suexec(
									username,
									new File(oldestLsa.getHome().toString()),
									command2,
									15
								);
							}
						}
					}

					// Remove the files processed (or not processed based on integration mode) in this pass
					for(PosixFile uf : thisPass) {
						uf.delete();
					}
				}
			}
		} catch(ValidationException e) {
			throw new IOException(e);
		}
	}

	/**
	 * Gets the {@link Bind} for the <code>spamd</code> process, or {@code null}
	 * if SpamAssassin is not enabled.
	 * <p>
	 * Note: CentOS 7 supports more than one bind for spamd, but we have no need for it at this time.
	 * </p>
	 */
	public static Bind getSpamdBind() throws IOException, SQLException {
		AOServConnector conn = AOServDaemon.getConnector();
		AppProtocol spamdProtocol = conn.getNet().getAppProtocol().get(AppProtocol.SPAMD);
		if(spamdProtocol == null) throw new SQLException("Unable to find AppProtocol: " + AppProtocol.SPAMD);
		List<Bind> spamdBinds = AOServDaemon.getThisServer().getHost().getNetBinds(spamdProtocol);
		if(spamdBinds.isEmpty()) {
			// Disabled
			return null;
		} else if(spamdBinds.size() == 1) {
			// Enabled on one port, as expected
			return spamdBinds.get(0);
		} else {
			throw new SQLException("spamd configured on more than one port: " + spamdBinds);
		}
	}

	private static final Object rebuildLock = new Object();
	@Override
	@SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
	protected boolean doRebuild() {
		try {
			Server thisServer = AOServDaemon.getThisServer();
			Host thisHost = thisServer.getHost();
			OperatingSystemVersion osv = thisHost.getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			// Used repeatedly below
			ByteArrayOutputStream bout = new ByteArrayOutputStream();

			synchronized(rebuildLock) {
				Set<PosixFile> restorecon = new LinkedHashSet<>();
				try {
					boolean hasSpecificAddress = false;

					final Bind spamdBind = getSpamdBind();
					final boolean spamdInstalled;
					boolean[] restartRequired = {false};
					if(spamdBind != null) {
						// Make sure package installed
						PackageManager.installPackage(
							PackageManager.PackageName.SPAMASSASSIN,
							() -> restartRequired[0] = true
						);
						spamdInstalled = true;
					} else {
						spamdInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.SPAMASSASSIN) != null;
					}
					if(spamdInstalled) {
						// Scale the number of children by system memory.
						long maxChildren = Math.max(
							(int)Math.min(
								ServerManager.getMemTotal() / PER_CHILD_MEMORY,
								MAX_CHILDREN
							),
							MIN_CHILDREN
						);

						/*
						 * Build the /etc/sysconfig/spamassassin file.
						 */
						bout.reset();
						try (ChainWriter newOut = new ChainWriter(bout)) {
							InetAddress spamdInetAddress;
							Port spamdPort;
							if(spamdBind == null) {
								spamdInetAddress = null;
								spamdPort = null;
							} else {
								spamdInetAddress = spamdBind.getIpAddress().getInetAddress();
								spamdPort = spamdBind.getPort();
							}
							// Build a new file in RAM
							newOut.print(
								"#\n"
								+ "# Generated by ").print(SpamAssassinManager.class.getName()).print("\n"
								+ "#\n"
								+ "\n"
								+ "# Options to spamd\n"
								+ "SPAMDOPTIONS=\"-d -c -m" + maxChildren + " -H");
							if(spamdInetAddress != null) {
								if(!spamdInetAddress.isLoopback() && !spamdInetAddress.isUnspecified()) hasSpecificAddress = true;
								// Listen address
								newOut.print(" -i ");
								if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
									newOut.print(spamdInetAddress.toString());
								} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
									newOut.print(spamdInetAddress.toBracketedString());
								} else {
									throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
								}
								// -4 or -6 switches for address family
								if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
									// No -4 or -6 switches
								} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
									ProtocolFamily family = spamdInetAddress.getProtocolFamily();
									if(family.equals(StandardProtocolFamily.INET)) {
										newOut.print(" -4");
									} else if(family.equals(StandardProtocolFamily.INET6)) {
										newOut.print(" -6");
									} else {
										throw new AssertionError("Unexpected family: " + family);
									}
								} else {
									throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
								}
							}
							if(spamdPort != null) {
								int portNum = spamdPort.getPort();
								if(portNum != DEFAULT_SPAMD_PORT) {
									newOut.print(" -p ").print(portNum);
								}
							}
							if(spamdInetAddress != null) {
								// Allowed addresses
								newOut.print(" -A ");
								if(spamdInetAddress.isLoopback()) {
									// Only 127.0.0.1 or ::1 expected at this time
									if(
										spamdInetAddress.equals(InetAddress.LOOPBACK_IPV4)
										|| spamdInetAddress.equals(InetAddress.LOOPBACK_IPV6)
									) {
										newOut.print(spamdInetAddress);
									} else {
										throw new SQLException("Unexpected loopback IP: " + spamdInetAddress);
									}
								} else {
									// Allow all IP addresses for this machine that are in the same family
									ProtocolFamily spamdFamily = spamdInetAddress.getProtocolFamily();
									Set<InetAddress> usedIps = new HashSet<>();
									for(IpAddress ip : thisHost.getIPAddresses()) {
										InetAddress addr = ip.getInetAddress();
										if(
											!addr.isUnspecified()
											&& !ip.getDevice().getDeviceId().isLoopback()
											&& ip.getInetAddress().getProtocolFamily().equals(spamdFamily)
											// TODO: Should we also filter by on the same Device?  (consider dual NICs, one private, one not)
										) {
											if(!usedIps.contains(addr)) {
												if(!usedIps.isEmpty()) newOut.print(',');
												newOut.print(addr.toString());
												usedIps.add(addr);
											}
										}
									}
									// Allow the primary IP of our current failover server
									/*
									Server failoverServer = server.getFailoverServer();
									if(failoverServer != null) {
										IpAddress foPrimaryIP = failoverServer.getPrimaryIPAddress();
										if(foPrimaryIP == null) throw new SQLException("Unable to find Primary IP Address for failover server: " + failoverServer);
										String addr = foPrimaryIP.getInetAddress();
										if(!usedIps.contains(addr)) {
											if(!usedIps.isEmpty()) newOut.print(',');
											newOut.print(addr);
											usedIps.add(addr);
										}
									}*/
									if(usedIps.isEmpty()) throw new AssertionError("No ip addresses in the same family as " + spamdInetAddress);
								}
							}
							newOut.print("\"\n");
							if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
								newOut.print("\n"
									   + "# Run at nice level of 10\n"
									   + "NICELEVEL=\"+10\"\n");
							} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
								// Nice level no longer set with multi-core systems now the norm
							} else {
								throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
							}
						}
						// Compare to existing
						if(
							DaemonFileUtils.atomicWrite(
								configPosixFile,
								bout.toByteArray(),
								0644,
								PosixFile.ROOT_UID,
								PosixFile.ROOT_GID,
								null,
								restorecon
							)
						) {
							// Flag to restart below
							restartRequired[0] = true;
						}
						/**
						 * Build the /etc/mail/spamassassin/local.cf file.
						 */
						bout.reset();
						try (ChainWriter newOut = new ChainWriter(bout)) {
							if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
								newOut.print(
									"#\n"
									+ "# Generated by ").print(SpamAssassinManager.class.getName()).print("\n"
									+ "#\n"
									+ "\n"
									+ "# These values can be overridden by editing ~/.spamassassin/user_prefs.cf\n"
									+ "# (see spamassassin(1) for details)\n"
									+ "\n"
									+ "# These should be safe assumptions and allow for simple visual sifting\n"
									+ "# without risking lost emails.\n"
									+ "\n"
									+ "report_safe 0\n"
									+ "lock_method flock\n"
									+ "required_score 3.0\n"
									+ "add_header all Level _STARS(*)_\n"
									+ "dns_available yes\n"
									+ "bayes_ignore_header X-Loop\n"
									+ "bayes_ignore_header X-Mozilla-Status\n"
									+ "bayes_ignore_header X-Mozilla-Status2\n"
									+ "bayes_ignore_header X-Sieve\n"
									+ "score FH_DATE_PAST_20XX 0.0\n"
									+ "score DNS_FROM_OPENWHOIS 0.0\n"
									+ "# Make auto learn more selective: http://wiki.apache.org/spamassassin/BasicConfiguration\n"
									+ "bayes_auto_learn_threshold_nonspam -0.1\n"
									+ "\n"
									+ "# The owner of the ahbl.org RBL disabled the RBL by failing it positive on January 01, 2014\n"
									+ "score DNS_FROM_AHBL_RHSBL 0\n"
									+ "\n"
									+ "# Don't want freemail alone to put into Junk folder with default required_score of 3.0\n"
									+ "# Default was 1.2:\n"
									+ "score FREEMAIL_REPLYTO_END_DIGIT 0.8\n"
									+ "# Default was 1.6:\n"
									+ "score FREEMAIL_ENVFROM_END_DIGIT 1.2"
								);
							} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
								newOut.print(
									"#\n"
									+ "# Generated by ").print(SpamAssassinManager.class.getName()).print("\n"
									+ "#\n"
									+ "\n"
									+ "# These values can be overridden by editing ~/.spamassassin/user_prefs.cf\n"
									+ "# (see spamassassin(1) for details)\n"
									+ "\n"
									+ "# These should be safe assumptions and allow for simple visual sifting\n"
									+ "# without risking lost emails.\n"
									+ "\n"
									+ "# See https://spamassassin.apache.org/full/3.4.x/doc/Mail_SpamAssassin_Conf.html\n"
									+ "\n"
									+ "# More aggressive than default of 5\n"
									+ "required_score 3.0\n"
									+ "\n"
									+ "# Disable test that had hard-coded year of 2010\n"
									+ "# See https://wiki.apache.org/spamassassin/Rules/FH_DATE_PAST_20XX\n"
									+ "score FH_DATE_PAST_20XX 0.0\n"
									+ "\n"
									+ "# Disable rule from dead blacklist\n"
									+ "# See https://wiki.apache.org/spamassassin/Rules/DNS_FROM_OPENWHOIS\n"
									+ "score DNS_FROM_OPENWHOIS 0.0\n"
									+ "\n"
									+ "# The owner of the ahbl.org RBL disabled the RBL by failing it positive on January 01, 2014\n"
									+ "# See https://bugs.debian.org/cgi-bin/bugreport.cgi?bug=774768\n"
									+ "score DNS_FROM_AHBL_RHSBL 0\n"
									// They seem to have tweaked the FREEMAIL scores: https://bz.apache.org/SpamAssassin/show_bug.cgi?id=6744
									//+ "\n"
									//+ "# Don't want freemail alone to put into Junk folder with default required_score of 3.0\n"
									//+ "# Default was 1.2:\n"
									//+ "score FREEMAIL_REPLYTO_END_DIGIT 0.8\n"
									//+ "# Default was 1.6:\n"
									//+ "score FREEMAIL_ENVFROM_END_DIGIT 1.2"
									+ "\n"
									+ "# Add a stars header, which is matched in .procmailrc to completely discard super spammy messages\n"
									+ "add_header all Level _STARS(*)_\n"
									+ "\n"
									+ "# Update headers only\n"
									+ "report_safe 0\n"
									+ "\n"
									+ "# DNS should always be available, skip auto detection\n"
									+ "dns_available yes\n"
									+ "\n"
									+ "# Configure Bayesian classifier\n"
									+ "bayes_ignore_header X-Loop\n"
									+ "bayes_ignore_header X-Mozilla-Status\n"
									+ "bayes_ignore_header X-Mozilla-Status2\n"
									+ "bayes_ignore_header X-Sieve\n"
									+ "# Make auto learn more selective\n"
									+ "# See https://spamassassin.apache.org/full/3.4.x/doc/Mail_SpamAssassin_Plugin_AutoLearnThreshold.html\n"
									+ "# See https://lists.gt.net/spamassassin/users/187706\n"
									+ "bayes_auto_learn_threshold_nonspam -0.1\n"
									+ "\n"
									+ "# Use faster locking since home directories not on NFS\n"
									+ "lock_method flock\n"
								);
							} else {
								throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
							}
						}
						// Compare to existing
						if(
							DaemonFileUtils.atomicWrite(
								localCfPosixFile,
								bout.toByteArray(),
								0644,
								PosixFile.ROOT_UID,
								PosixFile.ROOT_GID,
								null,
								restorecon
							)
						) {
							// Flag to restart below
							restartRequired[0] = true;
						}
						// SELinux before next steps
						DaemonFileUtils.restorecon(restorecon);
						restorecon.clear();
						if(spamdBind == null) {
							// SpamAssassin installed but disabled
							if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
								// Stop service if running
								if(subsysLockFile.exists()) {
									AOServDaemon.exec("/etc/rc.d/init.d/spamassassin", "stop");
									if(subsysLockFile.exists()) throw new IOException(subsysLockFile.getPath() + " still exists after service stop");
								}
								// chkconfig off if needed
								if(spamassassinRcFile.getStat().exists()) {
									AOServDaemon.exec("/sbin/chkconfig", "spamassassin", "off");
									if(spamassassinRcFile.getStat().exists()) throw new IOException(spamassassinRcFile.getPath() + " still exists after chkconfig off");
								}
							} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
								AOServDaemon.exec("/usr/bin/systemctl", "stop", "spamassassin.service");
								AOServDaemon.exec("/usr/bin/systemctl", "disable", "spamassassin.service");
							} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
						} else {
							// SpamAssassin installed and enabled
							if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
								// chkconfig on if needed
								if(!spamassassinRcFile.getStat().exists()) {
									AOServDaemon.exec("/sbin/chkconfig", "spamassassin", "on");
									if(!spamassassinRcFile.getStat().exists()) throw new IOException(spamassassinRcFile.getPath() + " still does not exist after chkconfig on");
								}
								// Start service if not running
								if(!subsysLockFile.exists()) {
									AOServDaemon.exec("/etc/rc.d/init.d/spamassassin", "start");
									if(!subsysLockFile.exists()) throw new IOException(subsysLockFile.getPath() + " still does not exist after service start");
								} else {
									if(restartRequired[0]) {
										AOServDaemon.exec("/etc/rc.d/init.d/spamassassin", "restart");
									}
								}
							} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
								AOServDaemon.exec("/usr/bin/systemctl", "enable", "spamassassin.service");
								if(restartRequired[0]) {
									AOServDaemon.exec("/usr/bin/systemctl", "restart", "spamassassin.service");
								} else {
									AOServDaemon.exec("/usr/bin/systemctl", "start", "spamassassin.service");
								}
								// Install spamassassin-after-network-online package on CentOS 7 when needed
								if(hasSpecificAddress) {
									PackageManager.installPackage(PackageManager.PackageName.SPAMASSASSIN_AFTER_NETWORK_ONLINE);
								}
							} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
						}
						// Uninstall spamassassin-after-network-online package on CentOS 7 when not needed
						if(
							!hasSpecificAddress
							&& osvId == OperatingSystemVersion.CENTOS_7_X86_64
							&& AOServDaemonConfiguration.isPackageManagerUninstallEnabled()
						) {
							PackageManager.removePackage(PackageManager.PackageName.SPAMASSASSIN_AFTER_NETWORK_ONLINE);
						}
					}

					/**
					 * Build the spamassassin files per account.
					 */
					int uid_min = thisServer.getUidMin().getId();
					int gid_min = thisServer.getGidMin().getId();
					List<UserServer> lsas = thisServer.getLinuxServerAccounts();
					for(UserServer lsa : lsas) {
						// Only build spamassassin for accounts under /home/
						SpamAssassinMode integrationMode = lsa.getEmailSpamAssassinIntegrationMode();
						// Only write files when SpamAssassin is turned on
						if(!integrationMode.getName().equals(SpamAssassinMode.NONE)) {
							if(!lsa.getLinuxAccount().getType().isEmail()) {
								throw new SQLException("SpamAssassin integration enabled on a non-email type user: " + lsa);
							}
							PosixPath homePath = lsa.getHome();
							if(!homePath.toString().startsWith("/home/")) {
								throw new SQLException("SpamAssassin integration enabled on a user with home directory outside /home: " + lsa + " at " + homePath);
							}
							if(spamdBind == null) throw new SQLException("Account has SpamAssassin integration enabled, but SpamAssassin is not configured in net_binds: " + lsa);
							PosixFile homeDir = new PosixFile(homePath.toString());
							PosixFile spamAssassinDir = new PosixFile(homeDir, ".spamassassin", false);
							// Create the .spamassassin directory if it doesn't exist
							if(!spamAssassinDir.getStat().exists()) {
								spamAssassinDir.mkdir(
									false,
									0700,
									lsa.getUid().getId(),
									lsa.getPrimaryLinuxServerGroup().getGid().getId()
								);
							}
							PosixFile userPrefs = new PosixFile(spamAssassinDir, "user_prefs", false);
							// Build the new file in RAM
							byte[] newBytes;
							{
								bout.reset();
								try (ChainWriter newOut = new ChainWriter(bout)) {
									newOut.print(
										"#\n"
										+ "# Generated by ").print(SpamAssassinManager.class.getName()).print("\n"
										+ "#\n"
										+ "required_score ").print(lsa.getSpamAssassinRequiredScore()).print('\n');
									if(integrationMode.getName().equals(SpamAssassinMode.POP3)) {
										newOut.print("rewrite_header Subject *****SPAM*****\n");
									}
								}
								newBytes = bout.toByteArray();
							}

							// Compare to existing
							if(!userPrefs.getStat().exists() || !userPrefs.contentEquals(newBytes)) {
								// Replace when changed
								PosixFile userPrefsNew = new PosixFile(spamAssassinDir, "user_prefs.new", false);
								try (FileOutputStream out = userPrefsNew.getSecureOutputStream(lsa.getUid().getId(), lsa.getPrimaryLinuxServerGroup().getGid().getId(), 0600, true, uid_min, gid_min)) {
									out.write(newBytes);
								}
								userPrefsNew.renameTo(userPrefs);
								restorecon.add(userPrefs);
							}
						}
					}
				} finally {
					DaemonFileUtils.restorecon(restorecon);
				}
			}
			return true;
		} catch(ThreadDeath td) {
			throw td;
		} catch(Throwable t) {
			logger.log(Level.SEVERE, null, t);
			return false;
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild SpamAssassin User Preferences";
	}

	@Override
	public long getProcessTimerMaximumTime() {
		return 30L * 60 * 1000;
	}

	/**
	 * TODO: Make this be a standard Unix-based cron job, outside aoserv-daemon
	 * package, so that functionality remains when aoserv-daemon disabled or
	 * uninstalled.
	 * <p>
	 * TODO: Check for compatibility with CentOS 7
	 * </p>
	 */
	public static class RazorLogTrimmer implements CronJob {

		private static final int NUM_LINES_RETAINED = 1000;

		private static final Schedule schedule =
			(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year)
			-> minute==5 && hour==1
		;

		@Override
		public Schedule getSchedule() {
			return schedule;
		}

		/**
		 * Once a day, all of the razor-agent.log files are cleaned to only include the last 1000 lines.
		 */
		@Override
		@SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
		public void run(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
			try {
				Set<PosixFile> restorecon = new LinkedHashSet<>();
				try {
					Server thisServer = AOServDaemon.getThisServer();
					int uid_min = thisServer.getUidMin().getId();
					int gid_min = thisServer.getGidMin().getId();
					Queue<String> queuedLines = new LinkedList<>();
					for(UserServer lsa : thisServer.getLinuxServerAccounts()) {
						// Only clean razor for accounts under /home/
						PosixPath homePath = lsa.getHome();
						if(lsa.getLinuxAccount().getType().isEmail() && homePath.toString().startsWith("/home/")) {
							PosixFile home = new PosixFile(homePath.toString());
							PosixFile dotRazor = new PosixFile(home, ".razor", false);
							PosixFile razorAgentLog = new PosixFile(dotRazor, "razor-agent.log", false);
							if(razorAgentLog.getStat().exists()) {
								try {
									boolean removed = false;
									try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(razorAgentLog.getFile())))) {
										queuedLines.clear();
										String line;
										while((line = in.readLine()) != null) {
											queuedLines.add(line);
											if(queuedLines.size() > NUM_LINES_RETAINED) {
												queuedLines.remove();
												removed = true;
											}
										}
									}
									if(removed) {
										int uid = lsa.getUid().getId();
										int gid = lsa.getPrimaryLinuxServerGroup().getGid().getId();
										try (
											TempFileContext tempFileContext = new TempFileContext(razorAgentLog.getFile().getParentFile());
											TempFile tempFile = tempFileContext.createTempFile(razorAgentLog.getFile().getName())
										) {
											PosixFile tempUF = new PosixFile(tempFile.getFile());
											try (PrintWriter out = new PrintWriter(new BufferedOutputStream(tempUF.getSecureOutputStream(uid, gid, 0644, true, uid_min, gid_min)))) {
												while(!queuedLines.isEmpty()) {
													out.println(queuedLines.remove());
												}
											}
											FileUtils.rename(tempFile.getFile(), razorAgentLog.getFile());
											restorecon.add(razorAgentLog);
										}
									}
								} catch(IOException err) {
									logger.log(Level.WARNING, "lsa = " + lsa, err);
								}
							}
						}
					}
				} finally {
					DaemonFileUtils.restorecon(restorecon);
				}
			} catch(ThreadDeath td) {
				throw td;
			} catch(Throwable t) {
				logger.log(Level.SEVERE, null, t);
			}
		}

		@Override
		public int getThreadPriority() {
			return Thread.MIN_PRIORITY;
		}
	}
}
