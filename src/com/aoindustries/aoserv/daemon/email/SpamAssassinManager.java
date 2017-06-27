/*
 * Copyright 2005-2013, 2015, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.email;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.EmailSpamAssassinIntegrationMode;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Protocol;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.client.validator.LinuxId;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.client.validator.UserId;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.cron.CronDaemon;
import com.aoindustries.cron.CronJob;
import com.aoindustries.cron.CronJobScheduleMode;
import com.aoindustries.cron.Schedule;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.net.AddressFamily;
import com.aoindustries.net.InetAddress;
import com.aoindustries.net.Port;
import com.aoindustries.util.WrappedException;
import com.aoindustries.validation.ValidationException;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.logging.Level;

/**
 * The primary purpose of the manager is to decouple the IMAP server from the SpamAssassin training.
 * The training process is slow and makes the IMAP server hesitate in ways that interfere with the
 * mail client and user.
 *
 * The SpamAssassin manager looks for emails left in a specific directories by the IMAP server.
 * When these files are found, the SpamAssassin training command (sa-learn) is invoked
 * on these directories.  The directories start with either ham_ or spam_ depending on
 * the type of messages.  In order to maintain the expected behavior, the directories
 * are processed in chronological order.  That way if a user drags a message to the Junk
 * folder then back to the INBOX, it will not be considered spam.
 *
 * To help avoid any race conditions, only directories that are at least 1 minute old (or in the future by 1 or more minutes
 * to handle clock changes) are considered on each pass.  This gives the IMAP server at least one minute to write all of
 * its files.
 *
 * Multiple directories from one user are sent to sa-learn at once when possible for efficiency.
 *
 * TODO: SELinux port management for non-standard (other than 783) ports.
 *
 * TODO: Somehow report when users drag from Junk to elsewhere (ham training), perhaps we can tweak our sa_discard_score some
 *
 * @author  AO Industries, Inc.
 */
public class SpamAssassinManager extends BuilderThread implements Runnable {

	/**
	 * The interval to sleep after each pass.
	 */
	private static final long DELAY_INTERVAL = (long)60*1000;

	/**
	 * The maximum number of messages that will be sent to sa-learn as a single command.
	 * This is kept fairly low so the sa-learn --sync doesn't take too long and hold
	 * locks that could prevent new mail from arriving.  Between executions, new
	 * mail would have a chance to be scanned.
	 */
	private static final int MAX_SALEARN_BATCH = 100;

	/**
	 * The minimum number of messages being sent to sa-learn that will use a two step
	 * sa-learn --no-sync then sa-learn --sync
	 * 
	 * The choice of 5 here is arbitrary, we have not measured the performance of this versus other values.
	 * This needs to balance the overhead of the additional exec versus the overhead of the sync.
	 * This balance may consider that CPU is generally more plentiful than disk I/O.
	 */
	private static final int SALEARN_NOSYNC_THRESHOLD = 5;

	private static final int MAX_CHILDREN = 25;

	private static final int DEFAULT_SPAMD_PORT = 783;

	/**
	 * The directory containing the spam and ham directories.
	 */
	private static final UnixFile incomingDirectory = new UnixFile("/var/spool/aoserv/spamassassin");

	private static SpamAssassinManager spamAssassinManager;

	private static final UnixFile configUnixFile = new UnixFile("/etc/sysconfig/spamassassin");

	private static final File subsysLockFile = new File("/var/lock/subsys/spamassassin");

	private static final UnixFile spamassassinRcFile = new UnixFile("/etc/rc.d/rc3.d/S78spamassassin");

	private SpamAssassinManager() {
	}

	@Override
	public void run() {
		long lastStartTime = -1;
		while(true) {
			try {
				while(true) {
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
							LogFactory.getLogger(SpamAssassinManager.class).log(Level.WARNING, null, err);
							// Restore the interrupted status
							Thread.currentThread().interrupt();
						}
					}
					lastStartTime = System.currentTimeMillis();

					// Process incoming messages
					AOServer thisAOServer = AOServDaemon.getThisAOServer();
					OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
					int osvId = osv.getPkey();
					if(osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586) {
						processIncomingMessagesMandriva();
					} else if(
						osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
						|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
					) {
						processIncomingMessagesCentOs();
					} else {
						throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
					}
				}
			} catch(ThreadDeath TD) {
				throw TD;
			} catch(Throwable T) {
				LogFactory.getLogger(SpamAssassinManager.class).log(Level.SEVERE, null, T);
				try {
					Thread.sleep(60000);
				} catch(InterruptedException err) {
					LogFactory.getLogger(SpamAssassinManager.class).log(Level.WARNING, null, err);
					// Restore the interrupted status
					Thread.currentThread().interrupt();
				}
			}
		}
	}

	public static void start() throws IOException, SQLException {
		AOServer thisAOServer = AOServDaemon.getThisAOServer();
		OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
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
					osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					spamAssassinManager = new SpamAssassinManager();
					conn.getLinuxServerAccounts().addTableListener(spamAssassinManager, 0);
					conn.getIpAddresses().addTableListener(spamAssassinManager, 0);
					PackageManager.addPackageListener(spamAssassinManager);
					new Thread(spamAssassinManager, "SpamAssassinManager").start();
					// Once per day, the razor logs will be trimmed to only include the last 1000 lines
					CronDaemon.addCronJob(new RazorLogTrimmer(), LogFactory.getLogger(SpamAssassinManager.class));
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	/**
	 * The entire filename must be acceptable characters a-z, A-Z, 0-9, -, and _
	 * This is, along with the character restrictions for usernames, are the key aspects
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

	private synchronized static void processIncomingMessagesMandriva() throws IOException, SQLException {
		String[] fileList = incomingDirectory.list();
		if(fileList != null && fileList.length > 0) {
			// Get the list of UnixFile's of all messages that are at least one minute old or one minute in the future
			List<UnixFile> readyList = new ArrayList<>(fileList.length);
			for (String filename : fileList) {
				if(filename.startsWith("ham_") || filename.startsWith("spam_")) {
					// Must be a directory
					UnixFile uf = new UnixFile(incomingDirectory, filename, false);
					Stat ufStat = uf.getStat();
					if(ufStat.isDirectory()) {
						long mtime = ufStat.getModifyTime();
						if(mtime == -1) {
							LogFactory.getLogger(SpamAssassinManager.class).log(Level.WARNING, "incomingDirectory = " + incomingDirectory.getPath() + ", filename = " + filename, new IOException("getModifyTime() returned -1"));
						} else {
							long currentTime = System.currentTimeMillis();
							if(
								(mtime - currentTime) > 60000
								|| (currentTime - mtime) > 60000
							) {
								if(isFilenameOk(filename)) {
									readyList.add(uf);
								} else {
									LogFactory.getLogger(SpamAssassinManager.class).log(Level.WARNING, "incomingDirectory = " + incomingDirectory.getPath() + ", filename = " + filename, new IOException("Invalid directory name"));
								}
							}
						}
					} else {
						LogFactory.getLogger(SpamAssassinManager.class).log(Level.WARNING, "incomingDirectory = " + incomingDirectory.getPath() + ", filename = " + filename, new IOException("Not a directory"));
					}
				} else {
					LogFactory.getLogger(SpamAssassinManager.class).log(Level.WARNING, "incomingDirectory = " + incomingDirectory.getPath()+", filename = " + filename, new IOException("Unexpected filename, should start with spam_ or ham_"));
				}
			}
			if(!readyList.isEmpty()) {
				// Sort the list by oldest time first
				Collections.sort(readyList, (UnixFile uf1, UnixFile uf2) -> {
					try {
						long mtime1 = uf1.getStat().getModifyTime();
						long mtime2 = uf2.getStat().getModifyTime();
						return
							mtime1 < mtime2 ? -1
							: mtime1 > mtime2 ? 1
							: 0
						;
					} catch(IOException err) {
						throw new WrappedException(
							err,
							new Object[] {
								"uf1 = " + uf1.getPath(),
								"uf2 = " + uf2.getPath()
							}
						);
					}
				});

				// Work through the list from oldest to newest, and for each user batching as many spam or ham directories together as possible
				AOServer aoServer = AOServDaemon.getThisAOServer();
				StringBuilder tempSB = new StringBuilder();
				List<UnixFile> thisPass = new ArrayList<>();
				while(!readyList.isEmpty()) {
					thisPass.clear();
					UnixFile currentUF = readyList.get(0);
					int currentUID = currentUF.getStat().getUid();
					boolean currentIsHam = currentUF.getFile().getName().startsWith("ham_");
					thisPass.add(currentUF);
					readyList.remove(0);
					for(int c = 0; c < readyList.size(); c++) {
						UnixFile other = readyList.get(c);
						// Only consider batching/terminating batching for same UID
						if(other.getStat().getUid() == currentUID) {
							boolean otherIsHam = other.getFile().getName().startsWith("ham_");
							if(currentIsHam == otherIsHam) {
								// If both spam or both ham, batch to one call and remove from later processing
								thisPass.add(other);
								readyList.remove(c);
								c--;
							} else {
								// Mode for that user switched, termination batching loop
								break;
							}
						}
					}

					// Find the account based on UID
					LinuxServerAccount lsa;
					try {
						lsa = aoServer.getLinuxServerAccount(LinuxId.valueOf(currentUID));
					} catch(ValidationException e) {
						throw new IOException(e);
					}
					if(lsa == null) {
						LogFactory.getLogger(SpamAssassinManager.class).log(Level.WARNING, "aoServer = " + aoServer.getHostname() + ", currentUID = " + currentUID, new SQLException("Unable to find LinuxServerAccount"));
					} else {
						UserId username = lsa.getLinuxAccount().getUsername().getUsername();

						// Only train SpamAssassin when integration mode not set to none
						EmailSpamAssassinIntegrationMode integrationMode = lsa.getEmailSpamAssassinIntegrationMode();
						if(!integrationMode.getName().equals(EmailSpamAssassinIntegrationMode.NONE)) {
							// Make sure sa-learn is installed
							PackageManager.installPackage(PackageManager.PackageName.SPAMASSASSIN);
							// Call sa-learn for this pass
							tempSB.setLength(0);
							tempSB.append("/usr/bin/sa-learn ").append(currentIsHam ? "--ham" : "--spam").append(" --dir");
							for (UnixFile uf : thisPass) {
								tempSB.append(' ').append(uf.getPath());
							}
							AOServDaemon.suexec(
								username,
								tempSB.toString(),
								15
							);
						}

						// Remove the files processed (or not processed based on integration mode) in this pass
						for(UnixFile uf : thisPass) {
							// Change the ownership and mode to make sure directory entries are not replaced during delete
							uf.chown(UnixFile.ROOT_UID, UnixFile.ROOT_GID).setMode(0700);
							String[] list = uf.list();
							if(list != null) {
								for(String filename : list) {
									new UnixFile(uf, filename, false).delete();
								}
							}
							uf.delete();
						}
					}
				}
			}
		}
	}

	private synchronized static void processIncomingMessagesCentOs() throws IOException, SQLException {
		try {
			// Only process incoming messages when the incoming directory exists
			if(incomingDirectory.getStat().exists()) {
				AOServer thisAoServer = AOServDaemon.getThisAOServer();
				int mailGid;
				{
					LinuxServerGroup mail = thisAoServer.getLinuxServerGroup(LinuxGroup.MAIL);
					if(mail == null) throw new SQLException("Unable to find LinuxServerGroup: " + LinuxAccount.MAIL + " on " + thisAoServer);
					mailGid = mail.getGid().getId();
				}

				// Used on inner loop
				List<File> deleteFileList = new ArrayList<>();
				StringBuilder tempSB = new StringBuilder();
				List<UnixFile> thisPass = new ArrayList<>(MAX_SALEARN_BATCH);

				while(true) {
					// End loop if no subdirectories
					String[] incomingDirectoryList = incomingDirectory.list();
					if(incomingDirectoryList == null || incomingDirectoryList.length == 0) break;

					// Find the username that has the oldest timestamp that is also at least one minute old or one minute in the future
					LinuxServerAccount oldestLsa = null;
					Map<UnixFile,Long> oldestReadyMap = null;
					long oldestTimestamp = -1;

					// The files will be backed-up before being deleted
					deleteFileList.clear();
					long currentTime = System.currentTimeMillis();

					for(String incomingDirectoryFilename : incomingDirectoryList) {
						UnixFile userDirectoryUf = new UnixFile(incomingDirectory, incomingDirectoryFilename, false);
						File userDirectoryFile = userDirectoryUf.getFile();

						// Each filename should be a username
						LinuxServerAccount lsa = thisAoServer.getLinuxServerAccount(UserId.valueOf(incomingDirectoryFilename));
						if(lsa == null) {
							// user not found, backup and then remove
							LogFactory.getLogger(SpamAssassinManager.class).log(Level.WARNING, "incomingDirectoryFilename = " + incomingDirectoryFilename, new IOException("User not found, deleting"));
							deleteFileList.add(userDirectoryFile);
						} else if(!lsa.getLinuxAccount().getType().isEmail()) {
							// user not email type, backup and then remove
							LogFactory.getLogger(SpamAssassinManager.class).log(Level.WARNING, "incomingDirectoryFilename = " + incomingDirectoryFilename, new IOException("User not email type, deleting"));
							deleteFileList.add(userDirectoryFile);
						} else if(!lsa.getHome().toString().startsWith("/home/")) {
							// user doesn't have home directory in /home/, backup and then remove
							LogFactory.getLogger(SpamAssassinManager.class).log(Level.WARNING, "incomingDirectoryFilename = " + incomingDirectoryFilename, new IOException("User home not in /home/, deleting"));
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
								Map<UnixFile,Long> readyMap = new HashMap<>(userDirectoryList.length * 4/3+1);
								for(String userFilename : userDirectoryList) {
									UnixFile userUf = new UnixFile(userDirectoryUf, userFilename, false);
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
															readyMap.put(userUf, timestamp);

															// Is the oldest?
															if(oldestLsa == null || timestamp < oldestTimestamp) {
																oldestLsa = lsa;
																oldestReadyMap = readyMap;
																oldestTimestamp = timestamp;
															}
														} else {
															LogFactory.getLogger(SpamAssassinManager.class).log(Level.WARNING, "userDirectoryUf = " + userDirectoryUf.getPath() + ", userFilename = " + userFilename, new IOException("Invalid character in filename, deleting"));
															deleteFileList.add(userFile);
														}
													}
												} catch(NumberFormatException err) {
													IOException ioErr = new IOException("Unable to find parse timestamp in filename, deleting");
													ioErr.initCause(err);
													LogFactory.getLogger(SpamAssassinManager.class).log(Level.WARNING, "userDirectoryUf = " + userDirectoryUf.getPath() + ", userFilename = " + userFilename, ioErr);
													deleteFileList.add(userFile);
												}
											} else {
												LogFactory.getLogger(SpamAssassinManager.class).log(Level.WARNING, "userDirectoryUf = " + userDirectoryUf.getPath() + ", userFilename = " + userFilename, new IOException("Unable to find second underscore (_) in filename, deleting"));
												deleteFileList.add(userFile);
											}
										} else {
											LogFactory.getLogger(SpamAssassinManager.class).log(Level.WARNING, "userDirectoryUf = " + userDirectoryUf.getPath() + ", userFilename = " + userFilename, new IOException("Not a regular file, deleting"));
											deleteFileList.add(userFile);
										}
									} else {
										LogFactory.getLogger(SpamAssassinManager.class).log(Level.WARNING, "userDirectoryUf = " + userDirectoryUf.getPath() + ", userFilename = " + userFilename, new IOException("Unexpected filename, should start with \"spam_\" or \"ham_\", deleting"));
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
					final Map<UnixFile,Long> readyMap = oldestReadyMap;
					List<UnixFile> readyList = new ArrayList<>(oldestReadyMap.keySet());
					Collections.sort(
						readyList,
						(UnixFile uf1, UnixFile uf2) -> readyMap.get(uf1).compareTo(readyMap.get(uf2))
					);

					// Process the oldest file while batching as many spam or ham directories together as possible
					thisPass.clear();
					UnixFile firstUf = readyList.get(0);
					boolean firstIsHam = firstUf.getFile().getName().startsWith("ham_");
					thisPass.add(firstUf);
					for(int c = 1; c < readyList.size(); c++) {
						UnixFile other = readyList.get(c);
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
					EmailSpamAssassinIntegrationMode integrationMode = oldestLsa.getEmailSpamAssassinIntegrationMode();
					if(!integrationMode.getName().equals(EmailSpamAssassinIntegrationMode.NONE)) {
						// Make sure sa-learn is installed
						PackageManager.installPackage(PackageManager.PackageName.SPAMASSASSIN);
						// Call sa-learn for this pass
						UserId username = oldestLsa.getLinuxAccount().getUsername().getUsername();
						tempSB.setLength(0);
						tempSB.append("/usr/bin/sa-learn");
						boolean isNoSync = thisPass.size() >= SALEARN_NOSYNC_THRESHOLD;
						if(isNoSync) tempSB.append(" --no-sync");
						tempSB.append(firstIsHam ? " --ham" : " --spam");
						for(UnixFile uf : thisPass) tempSB.append(' ').append(uf.getPath());
						String command = tempSB.toString();
						//System.err.println("DEBUG: "+SpamAssassinManager.class.getName()+": processIncomingMessagesCentOs: username="+username+" and command=\""+command+"\"");
						try {
							AOServDaemon.suexec(
								username,
								command,
								15
							);
						} finally {
							if(isNoSync) {
								String command2 = "/usr/bin/sa-learn --sync";
								//System.err.println("DEBUG: "+SpamAssassinManager.class.getName()+": processIncomingMessagesCentOs: username="+username+" and command2=\""+command2+"\"");
								AOServDaemon.suexec(
									username,
									command2,
									15
								);
							}
						}
					}

					// Remove the files processed (or not processed based on integration mode) in this pass
					for(UnixFile uf : thisPass) uf.delete();
				}
			}
		} catch(ValidationException e) {
			throw new IOException(e);
		}
	}

	/**
	 * Gets the {@link NetBind} for the <code>spamd</code> process, or {@code null}
	 * if SpamAssassin is not enabled.
	 *
	 * Note: CentOS 7 supports more than one bind for spamd, but we have not need for it at this time.
	 */
	public static NetBind getSpamdBind() throws IOException, SQLException {
		AOServConnector conn = AOServDaemon.getConnector();
		Protocol spamdProtocol = conn.getProtocols().get(Protocol.SPAMD);
		if(spamdProtocol == null) throw new SQLException("Unable to find Protocol: " + Protocol.SPAMD);
		List<NetBind> spamdBinds = AOServDaemon.getThisAOServer().getServer().getNetBinds(spamdProtocol);
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
	protected boolean doRebuild() {
		try {
			AOServer thisAoServer = AOServDaemon.getThisAOServer();
			Server thisServer = thisAoServer.getServer();
			OperatingSystemVersion osv = thisServer.getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
				&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			// Used repeatedly below
			ByteArrayOutputStream bout = new ByteArrayOutputStream();

			synchronized(rebuildLock) {
				Set<UnixFile> restorecon = new LinkedHashSet<>();
				try {
					final NetBind spamdBind = getSpamdBind();
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
						/**
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
								spamdInetAddress = spamdBind.getIPAddress().getInetAddress();
								spamdPort = spamdBind.getPort();
							}
							// Build a new file in RAM
							newOut.print(
								"#\n"
								+ "# Generated by ").print(SpamAssassinManager.class.getName()).print("\n"
								+ "#\n"
								+ "\n"
								+ "# Options to spamd\n"
								+ "SPAMDOPTIONS=\"-d -c -m" + MAX_CHILDREN + " -H");
							if(spamdInetAddress != null) {
								// Listen address
								newOut.print("-i ");
								if(
									osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
									|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
								) {
									newOut.print(spamdInetAddress.toString());
								} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
									newOut.print(spamdInetAddress.toBracketedString());
								} else {
									throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
								}
								// -4 or -6 switches for address family
								if(
									osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
									|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
								) {
									// No -4 or -6 switches
								} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
									switch(spamdInetAddress.getAddressFamily()) {
										case INET :
											newOut.print(" -4");
											break;
										case INET6 :
											newOut.print(" -6");
											break;
										default :
											throw new AssertionError();
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
									AddressFamily spamdFamily = spamdInetAddress.getAddressFamily();
									Set<InetAddress> usedIps = new HashSet<>();
									for(IPAddress ip : thisServer.getIPAddresses()) {
										InetAddress addr = ip.getInetAddress();
										if(
											!addr.isUnspecified()
											&& !ip.getNetDevice().getNetDeviceID().isLoopback()
											&& ip.getInetAddress().getAddressFamily() == spamdFamily
											// TODO: Should we also filter by on the same NetDevice?  (consider dual NICs, one private, one not)
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
									AOServer failoverServer = server.getFailoverServer();
									if(failoverServer != null) {
										IPAddress foPrimaryIP = failoverServer.getPrimaryIPAddress();
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
							if(
								osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
								|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
							) {
								newOut.print("\n"
									   + "# Run at nice level of 10\n"
									   + "NICELEVEL=\"+10\"\n");
							} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
								// TODO: Set nice for CentOS 7?
							} else {
								throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
							}
						}
						// Compare to existing
						if(
							DaemonFileUtils.atomicWrite(
								configUnixFile,
								bout.toByteArray(),
								0644,
								UnixFile.ROOT_UID,
								UnixFile.ROOT_GID,
								null,
								restorecon
							)
						) {
							// SELinux before next steps
							DaemonFileUtils.restorecon(restorecon);
							restorecon.clear();
							// Flag to restart below
							restartRequired[0] = true;
						}
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
								try {
									AOServDaemon.exec("/usr/bin/systemctl", "is-enabled", "--quiet", "spamassassin.service");
								} catch(IOException e) {
									// Non-zero response indicates not enabled
									AOServDaemon.exec("/usr/bin/systemctl", "enable", "spamassassin.service");
								}
								if(restartRequired[0]) {
									AOServDaemon.exec("/usr/bin/systemctl", "restart", "spamassassin.service");
								} else {
									AOServDaemon.exec("/usr/bin/systemctl", "start", "spamassassin.service");
								}
							} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
						}
					}

					/**
					 * Build the spamassassin files per account.
					 */
					int uid_min = thisAoServer.getUidMin().getId();
					int gid_min = thisAoServer.getGidMin().getId();
					List<LinuxServerAccount> lsas = thisAoServer.getLinuxServerAccounts();
					for(LinuxServerAccount lsa : lsas) {
						// Only build spamassassin for accounts under /home/
						EmailSpamAssassinIntegrationMode integrationMode = lsa.getEmailSpamAssassinIntegrationMode();
						// Only write files when SpamAssassin is turned on
						if(!integrationMode.getName().equals(EmailSpamAssassinIntegrationMode.NONE)) {
							if(!lsa.getLinuxAccount().getType().isEmail()) {
								throw new SQLException("SpamAssassin integration enabled on a non-email type user: " + lsa);
							}
							UnixPath homePath = lsa.getHome();
							if(!homePath.toString().startsWith("/home/")) {
								throw new SQLException("SpamAssassin integration enabled on a user with home directory outside /home: " + lsa + " at " + homePath);
							}
							if(spamdBind == null) throw new SQLException("Account has SpamAssassin integration enabled, but SpamAssassin is not configured in net_binds: " + lsa);
							UnixFile homeDir = new UnixFile(homePath.toString());
							UnixFile spamAssassinDir = new UnixFile(homeDir, ".spamassassin", false);
							// Create the .spamassassin directory if it doesn't exist
							if(!spamAssassinDir.getStat().exists()) {
								spamAssassinDir.mkdir(
									false,
									0700,
									lsa.getUid().getId(),
									lsa.getPrimaryLinuxServerGroup().getGid().getId()
								);
							}
							UnixFile userPrefs = new UnixFile(spamAssassinDir, "user_prefs", false);
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
									if(integrationMode.getName().equals(EmailSpamAssassinIntegrationMode.POP3)) {
										newOut.print("rewrite_header Subject *****SPAM*****\n");
									}
								}
								newBytes = bout.toByteArray();
							}

							// Compare to existing
							if(!userPrefs.getStat().exists() || !userPrefs.contentEquals(newBytes)) {
								// Replace when changed
								UnixFile userPrefsNew = new UnixFile(spamAssassinDir, "user_prefs.new", false);
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
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(SpamAssassinManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild SpamAssassin User Preferences";
	}

	@Override
	public long getProcessTimerMaximumTime() {
		return (long)30*60*1000;
	}

	/**
	 * TODO: Make this be a standard Unix-based cron job, outside aoserv-daemon
	 * package, so that functionality remains when aoserv-daemon disabled or
	 * uninstalled.
	 *
	 * TODO: Check for compatibility with CentOS 7
	 */
	public static class RazorLogTrimmer implements CronJob {

		private static final int NUM_LINES_RETAINED = 1000;

		private static final Schedule schedule =
			(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year)
			-> minute==5 && hour==1
		;

		@Override
		public Schedule getCronJobSchedule() {
			return schedule;
		}

		@Override
		public CronJobScheduleMode getCronJobScheduleMode() {
			return CronJobScheduleMode.SKIP;
		}

		@Override
		public String getCronJobName() {
			return "RazorLogTrimmer";
		}

		/**
		 * Once a day, all of the razor-agent.log files are cleaned to only include the last 1000 lines.
		 */
		@Override
		public void runCronJob(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
			try {
				Set<UnixFile> restorecon = new LinkedHashSet<>();
				try {
					AOServer thisAoServer = AOServDaemon.getThisAOServer();
					int uid_min = thisAoServer.getUidMin().getId();
					int gid_min = thisAoServer.getGidMin().getId();
					Queue<String> queuedLines = new LinkedList<>();
					for(LinuxServerAccount lsa : thisAoServer.getLinuxServerAccounts()) {
						// Only clean razor for accounts under /home/
						UnixPath homePath = lsa.getHome();
						if(lsa.getLinuxAccount().getType().isEmail() && homePath.toString().startsWith("/home/")) {
							UnixFile home = new UnixFile(homePath.toString());
							UnixFile dotRazor = new UnixFile(home, ".razor", false);
							UnixFile razorAgentLog = new UnixFile(dotRazor, "razor-agent.log", false);
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
										UnixFile tempFile = UnixFile.mktemp(razorAgentLog.getPath() + '.', false);
										try {
											try (PrintWriter out = new PrintWriter(new BufferedOutputStream(tempFile.getSecureOutputStream(uid, gid, 0644, true, uid_min, gid_min)))) {
												while(!queuedLines.isEmpty()) out.println(queuedLines.remove());
											}
											tempFile.renameTo(razorAgentLog);
											restorecon.add(razorAgentLog);
										} finally {
											if(tempFile.getStat().exists()) tempFile.delete();
										}
									}
								} catch(IOException err) {
									LogFactory.getLogger(SpamAssassinManager.class).log(Level.WARNING, "lsa = " + lsa, err);
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
				LogFactory.getLogger(SpamAssassinManager.class).log(Level.SEVERE, null, t);
			}
		}

		@Override
		public int getCronJobThreadPriority() {
			return Thread.MIN_PRIORITY;
		}
	}
}
