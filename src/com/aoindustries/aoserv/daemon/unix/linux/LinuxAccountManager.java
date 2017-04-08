/*
 * Copyright 2001-2013, 2014, 2015, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.unix.linux;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.FTPGuestUser;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxAccountType;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Shell;
import com.aoindustries.aoserv.client.validator.Gecos;
import com.aoindustries.aoserv.client.validator.GroupId;
import com.aoindustries.aoserv.client.validator.LinuxId;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.client.validator.UserId;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.email.ImapManager;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.ShadowFile;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.CompressedDataInputStream;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.io.FileUtils;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.BufferManager;
import com.aoindustries.util.ErrorPrinter;
import com.aoindustries.util.Tuple2;
import com.aoindustries.validation.ValidationException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TODO: Keep original file ordering.
 * TODO: gshadow: Add alt users on gshadow, too
 * TODO: gshadow: maintain "!" and "!!": https://access.redhat.com/documentation/en-US/Red_Hat_Enterprise_Linux/4/html/Introduction_To_System_Administration/s3-acctsgrps-gshadow.html
 * TODO: add system groups to master as found
 * TODO: add system users to master as found
 *
 * @author  AO Industries, Inc.
 */
public class LinuxAccountManager extends BuilderThread {

	private static final Logger logger = Logger.getLogger(LinuxAccountManager.class.getName());

	private static final UnixFile
		newPasswd    = new UnixFile("/etc/passwd.new"),
		passwd       = new UnixFile("/etc/passwd"),
		backupPasswd = new UnixFile("/etc/passwd.old"),

		newGroup    = new UnixFile("/etc/group.new"),
		group       = new UnixFile("/etc/group"),
		backupGroup = new UnixFile("/etc/group.old"),

		newGShadow    = new UnixFile("/etc/gshadow.new"),
		gshadow       = new UnixFile("/etc/gshadow"),
		backupGShadow = new UnixFile("/etc/gshadow.old")
	;

	private static final String BASHRC = ".bashrc";

	private static final File cronDirectory = new File("/var/spool/cron");

	public static boolean comparePassword(UserId username, String password) throws IOException, SQLException {
		String crypted = ShadowFile.getEncryptedPassword(username).getElement1();
		if(crypted.equals(LinuxAccount.NO_PASSWORD_CONFIG_VALUE)) return false;
		int len = crypted.length();
		if(len < 2) return false;
		String salt;
		if(crypted.charAt(0) == '$') {
			// Select salt up to but not including the last '$'
			int lastPos = crypted.lastIndexOf('$');
			salt = crypted.substring(0, lastPos);
			if(salt.length() < 3) return false;
		} else {
			// Assume old-school DES
			salt = crypted.substring(0, 2);
		}
		String newCrypted = UnixFile.crypt(password, salt);
		return crypted.equals(newCrypted);
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			rebuildLinuxAccountSettings();
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			logger.log(Level.SEVERE, null, T);
			return false;
		}
	}

	private static void rebuildLinuxAccountSettings() throws IOException, SQLException {
		AOServConnector connector = AOServDaemon.getConnector();
		AOServer thisAoServer = AOServDaemon.getThisAOServer();
		HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();

		OperatingSystemVersion osv = thisAoServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		if(
			osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
			&& osvId != OperatingSystemVersion.REDHAT_ES_4_X86_64
			&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
			// TODO: CentOS 7 once system groups and users auto-added
		) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

		int uid_min = thisAoServer.getUidMin().getId();
		int gid_min = thisAoServer.getGidMin().getId();

		synchronized(rebuildLock) {
			// A list of all files to delete is created so that all the data can
			// be backed-up before removal.
			List<File> deleteFileList = new ArrayList<>();

			// Get the list of users from the database
			List<LinuxServerAccount> accounts = thisAoServer.getLinuxServerAccounts();

			// Install /usr/bin/ftppasswd and /usr/bin/ftponly if required by any LinuxServerAccount
			boolean hasFtpShell = false;
			for(LinuxServerAccount lsa : accounts) {
				UnixPath shellPath = lsa.getLinuxAccount().getShell().getPath();
				if(
					shellPath.equals(Shell.FTPONLY)
					|| shellPath.equals(Shell.FTPPASSWD)
				) {
					hasFtpShell = true;
					break;
				}
			}
			if(hasFtpShell) {
				PackageManager.installPackage(PackageManager.PackageName.AOSERV_FTP_SHELLS);
			}

			// Add /usr/bin/passwd to /etc/shells if required by any LinuxServerAccount
			boolean hasPasswdShell = false;
			for(LinuxServerAccount lsa : accounts) {
				if(lsa.getLinuxAccount().getShell().getPath().equals(Shell.PASSWD)) {
					hasPasswdShell = true;
					break;
				}
			}
			if(hasPasswdShell) {
				PackageManager.installPackage(PackageManager.PackageName.AOSERV_PASSWD_SHELL);
			}

			// Build sets of all the usernames, user ids, and home directories
			final Set<UserId> usernames;
			final Set<String> usernameStrs;
			final Set<Integer> uids;
			final Set<String> homeDirs;
			{
				int initialCapacity = accounts.size()*4/3+1;
				usernames    = new HashSet<>(initialCapacity);
				usernameStrs = new HashSet<>(initialCapacity);
				uids         = new HashSet<>(initialCapacity);
				homeDirs     = new HashSet<>(initialCapacity);
				boolean hasRoot = false;
				for(LinuxServerAccount lsa : accounts) {
					UserId username = lsa.getLinuxAccount().getUsername().getUsername();
					if(!usernames.add(username)) throw new SQLException("Duplicate username: " + username);
					if(!usernameStrs.add(username.toString())) throw new AssertionError();
					uids.add(lsa.getUid().getId());
					homeDirs.add(lsa.getHome().toString());
					if(username.equals(LinuxAccount.ROOT)) hasRoot = true;
				}
				if(!hasRoot) throw new SQLException(LinuxAccount.ROOT + " user not found");
			}

			byte[] newPasswdContent;
			byte[] newGroupContent;
			byte[] newGShadowContent;
			{
				ByteArrayOutputStream bout = new ByteArrayOutputStream();
				// Write the new /etc/passwd file.
				try (ChainWriter out = new ChainWriter(bout)) {
					// Write root first
					boolean rootFound = false;
					for(LinuxServerAccount account : accounts) {
						if(account.getLinuxAccount().getUsername().getUsername().equals(LinuxAccount.ROOT)) {
							printPasswdLine(account, out);
							rootFound = true;
							break;
						}
					}
					if(!rootFound) throw new SQLException("root user not found while creating " + newPasswd.getPath());
					for(LinuxServerAccount account : accounts) {
						if(!account.getLinuxAccount().getUsername().getUsername().equals(LinuxAccount.ROOT)) {
							printPasswdLine(account, out);
						}
					}
				}
				newPasswdContent = bout.toByteArray();

				/*
				 * Write the new /etc/group file.
				 */
				List<LinuxServerGroup> groups = thisAoServer.getLinuxServerGroups();
				bout.reset();
				try (ChainWriter out = new ChainWriter(bout)) {
					Set<UserId> tempSet = new HashSet<>();
					boolean rootFound = false;
					// Write root first
					for(LinuxServerGroup lsg : groups) {
						if(lsg.getLinuxGroup().getName().equals(LinuxGroup.ROOT)) {
							printGroupLine(lsg, out, tempSet);
							rootFound = true;
							break;
						}
					}
					if(!rootFound) throw new SQLException("root group not found while creating " + newGroup.getPath());
					for(LinuxServerGroup lsg : groups) {
						if(!lsg.getLinuxGroup().getName().equals(LinuxGroup.ROOT)) {
							printGroupLine(lsg, out, tempSet);
						}
					}
				}
				newGroupContent = bout.toByteArray();

				/*
				 * Write the new /etc/gshadow file.
				 */
				bout.reset();
				try (ChainWriter out = new ChainWriter(bout)) {
					// Write root first
					boolean rootFound = false;
					for(LinuxServerGroup lsg : groups) {
						if(lsg.getLinuxGroup().getName().equals(LinuxGroup.ROOT)) {
							printGShadowLine(lsg, out);
							rootFound = true;
							break;
						}
					}
					if(!rootFound) throw new SQLException("root group not found while creating " + newGShadow.getPath());
					for(LinuxServerGroup lsg : groups) {
						if(!lsg.getLinuxGroup().getName().equals(LinuxGroup.ROOT)) {
							printGShadowLine(lsg, out);
						}
					}
				}
				newGShadowContent = bout.toByteArray();
			}

			ShadowFile.rebuildShadowFile(usernames);

			/*
			 * Move the new files into place.
			 */
			if(!passwd.contentEquals(newPasswdContent)) {
				try (OutputStream out = newPasswd.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true, uid_min, gid_min)) {
					out.write(newPasswdContent);
				}
				if(newPasswd.getStat().getSize() <= 0) {
					throw new IOException(newPasswd + " is zero or unknown length");
				}
				passwd.renameTo(backupPasswd);
				newPasswd.renameTo(passwd);
			}

			if(!group.contentEquals(newGroupContent)) {
				try (OutputStream out = newGroup.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true, uid_min, gid_min)) {
					out.write(newGroupContent);
				}
				if(newGroup.getStat().getSize() <= 0) {
					throw new IOException(newGroup + " is zero or unknown length");
				}
				group.renameTo(backupGroup);
				newGroup.renameTo(group);
			}

			if(!gshadow.contentEquals(newGShadowContent)) {
				try (OutputStream out = newGShadow.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true, uid_min, gid_min)) {
					out.write(newGShadowContent);
				}
				if(newGShadow.getStat().getSize() <= 0) {
					throw new IOException(newGShadow + " is zero or unknown length");
				}
				if(
					osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
				) {
					// Remains 0600
				} else if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
					// Set to 0400
					newGShadow.setMode(0400);
				} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
					// Set to 0000
					newGShadow.setMode(0);
				} else {
					throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
				}
				gshadow.renameTo(backupGShadow);
				newGShadow.renameTo(gshadow);
			}

			if(osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586) {
				// Create any inboxes that need to exist.
				LinuxServerGroup mailGroup = connector.getLinuxGroups().get(LinuxGroup.MAIL).getLinuxServerGroup(thisAoServer);
				if(mailGroup == null) throw new SQLException("Unable to find LinuxServerGroup: " + LinuxGroup.MAIL + " on " + thisAoServer.getHostname());
				for(LinuxServerAccount account : accounts) {
					LinuxAccount linuxAccount = account.getLinuxAccount();
					if(linuxAccount.getType().isEmail()) {
						UserId username = linuxAccount.getUsername().getUsername();
						File file = new File(ImapManager.mailSpool, username.toString());
						if(!file.exists()) {
							UnixFile unixFile = new UnixFile(file.getPath());
							unixFile.getSecureOutputStream(
								account.getUid().getId(),
								mailGroup.getGid().getId(),
								0660,
								false,
								uid_min,
								gid_min
							).close();
						}
					}
				}

				// Remove any inboxes that should not exist.
				String[] list = ImapManager.mailSpool.list();
				if(list != null) {
					for(String filename : list) {
						if(!usernameStrs.contains(filename)) {
							// Also allow a username.lock file to remain
							if(
								!filename.endsWith(".lock")
								|| !usernameStrs.contains(filename.substring(0, filename.length() - ".lock".length()))
							) {
								File spoolFile = new File(ImapManager.mailSpool, filename);
								deleteFileList.add(spoolFile);
							}
						}
					}
				}
			} else if(
				osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
				|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
			) {
				// Nothing done, user management put in ImapManager
			} else {
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
			}

			/*
			 * Create any home directories that do not exist.
			 */
			for(LinuxServerAccount lsa : accounts) {
				LinuxAccount la = lsa.getLinuxAccount();
				String type = la.getType().getName();
				LinuxServerGroup primaryLsg = lsa.getPrimaryLinuxServerGroup();
				int uid = lsa.getUid().getId();
				int gid = primaryLsg.getGid().getId();

				boolean copySkel = false;

				final UnixFile homeDir = new UnixFile(lsa.getHome().toString());
				if(!homeDir.getStat().exists()) {
					// Make the parent of the home directory if it does not exist
					UnixFile parent = homeDir.getParent();
					if(!parent.getStat().exists()) {
						parent.mkdir(true, 0755);
					}
					// Make the home directory
					homeDir.mkdir(false, 0700);
					copySkel = true;
				}
				// Set up the directory if it was just created or was created as root before
				final String homeStr = homeDir.getPath();
				// Homes in /www will have all the skel copied, but will not set the directory perms
				boolean isWWWAndUser =
					homeStr.startsWith(osConfig.getHttpdSitesDirectory().toString() + '/')
					&& (type.equals(LinuxAccountType.USER) || type.equals(LinuxAccountType.APPLICATION))
					&& la.getFTPGuestUser() == null
				;
				// Only build directories for accounts that are in /home/ or user account in /www/
				if(
					isWWWAndUser
					|| homeStr.startsWith("/home/")
				) {
					boolean chownHome;
					{
						Stat homeDirStat = homeDir.getStat();
						chownHome = (
							!isWWWAndUser
							&& (
								homeDirStat.getUid() == UnixFile.ROOT_UID
								|| homeDirStat.getGid() == UnixFile.ROOT_GID
							)
							// Do not set permissions for encrypted home directories
							&& !(new UnixFile(homeStr + ".aes256.img").getStat().exists())
						);
						if(chownHome) copySkel = true;
					}
					// Copy the /etc/skel directory
					if(
						copySkel
						// Only copy the files for user accounts
						&& (type.equals(LinuxAccountType.USER) || type.equals(LinuxAccountType.APPLICATION))
					) {
						File skel = new File("/etc/skel");
						String[] skelList = skel.list();
						if(skelList != null) {
							for(String filename : skelList) {
								UnixFile skelFile = new UnixFile(skel, filename);
								UnixFile homeFile = new UnixFile(homeDir, filename, true);
								if(!homeFile.getStat().exists()) {
									skelFile.copyTo(homeFile, false);
									homeFile.chown(uid, gid);
								}
							}
						}
					}
					// Set final directory ownership now that home directory completely setup
					if(chownHome) {
						homeDir.chown(uid, gid);
						// Now done in mkdir above: homeDir.setMode(0700);
					}
				}
			}

			/*
			 * Remove any home directories that should not exist.
			 */
			// TODO: Review this carefully
			Set<String> keepHashDirs = new HashSet<>();
			for(char ch='a'; ch<='z'; ch++) {
				UnixFile hashDir = new UnixFile("/home/" + ch);
				if(!homeDirs.contains(hashDir.getPath())) {
					if(hashDir.getStat().exists()) {
						boolean hasHomeDir = false;
						List<File> hashDirToDelete = new ArrayList<>();
						String[] homeList = hashDir.list();
						if(homeList != null) {
							for(String dirName : homeList) {
								UnixFile dir = new UnixFile(hashDir, dirName, true);
								String dirPath = dir.getPath();
								// Allow encrypted form of home directory
								if(dirPath.endsWith(".aes256.img")) dirPath = dirPath.substring(0, dirPath.length() - ".aes256.img".length());
								if(homeDirs.contains(dirPath)) {
									hasHomeDir = true;
								} else {
									hashDirToDelete.add(dir.getFile());
								}
							}
						}
						if(hasHomeDir) {
							deleteFileList.addAll(hashDirToDelete);
							keepHashDirs.add(hashDir.getPath());
						}
					}
				}
			}
			// Direct children of /home
			UnixFile homeDir = new UnixFile("/home");
			String[] homeList = homeDir.list();
			if(homeList != null) {
				for(String dirName : homeList) {
					UnixFile dir = new UnixFile(homeDir, dirName, true);
					String dirPath = dir.getPath();
					if(!keepHashDirs.contains(dirPath)) {
						// Allow encrypted form of home directory
						if(dirPath.endsWith(".aes256.img")) dirPath = dirPath.substring(0, dirPath.length() - ".aes256.img".length());
						if(!homeDirs.contains(dirPath)) {
							deleteFileList.add(dir.getFile());
						}
					}
				}
			}

			/*
			 * Remove any cron jobs that should not exist.
			 */ 
			String[] cronList = cronDirectory.list();
			if(cronList != null) {
				for(String filename : cronList) {
					// Filename must be the username of one of the users to be kept intact
					if(!usernameStrs.contains(filename)) deleteFileList.add(new File(cronDirectory, filename));
				}
			}

			// Disable and enable accounts
			for(LinuxServerAccount lsa : accounts) {
				String prePassword = lsa.getPredisablePassword();
				if(lsa.isDisabled()) {
					// Account is disabled
					if(prePassword == null) {
						UserId username = lsa.getLinuxAccount().getUsername().getUsername();
						lsa.setPredisablePassword(getEncryptedPassword(username).getElement1());
						setPassword(username, null, false);
					}
				} else {
					// Account is enabled
					if(prePassword != null) {
						setEncryptedPassword(lsa.getLinuxAccount().getUsername().getUsername(), prePassword, null);
						lsa.setPredisablePassword(null);
					}
				}
			}

			// Only the top level server in a physical server gets to kill processes
			if(!AOServDaemonConfiguration.isNested() && thisAoServer.getFailoverServer() == null) {
				List<AOServer> nestedServers = thisAoServer.getNestedAOServers();

				/*
				 * Kill any processes that are running as a UID that
				 * should not exist on this server.
				 */
				File procDir = new File("/proc");
				String[] procList = procDir.list();
				if(procList != null) {
					for(String filename : procList) {
						int flen = filename.length();
						boolean allNum = true;
						for(int d = 0; d < flen; d++) {
							char ch = filename.charAt(d);
							if(ch<'0' || ch>'9') {
								allNum = false;
								break;
							}
						}
						if(allNum) {
							try {
								LinuxProcess process = new LinuxProcess(Integer.parseInt(filename));
								int uid = process.getUid();
								// Never kill root processes, just to be safe
								if(uid != LinuxServerAccount.ROOT_UID) {
									// Search each server
									LinuxServerAccount lsa;
									if(
										!uids.contains(uid)
										|| (lsa = thisAoServer.getLinuxServerAccount(LinuxId.valueOf(uid))) == null
										|| lsa.isDisabled()
									) {
										// Also must not be in a nested server
										boolean foundInNested = false;
										for(AOServer nestedServer : nestedServers) {
											lsa = nestedServer.getLinuxServerAccount(LinuxId.valueOf(uid));
											if(
												lsa != null
												&& !lsa.isDisabled()
											) {
												foundInNested = true;
												break;
											}
										}
										if(!foundInNested) process.killProc();
									}
								}
							} catch(FileNotFoundException err) {
								if(logger.isLoggable(Level.FINE)) {
									logger.log(Level.FINE, "It is normal that this is thrown if the process has already closed", err);
								}
							} catch(IOException | ValidationException err) {
								logger.log(Level.SEVERE, "filename=" + filename, err);
							}
						}
					}
				}
			}

			/*
			 * Recursively find and remove any temporary files that should not exist.
			 */
			try {
				AOServDaemon.findUnownedFiles(new File("/tmp"), uids, deleteFileList, 0);
				AOServDaemon.findUnownedFiles(new File("/var/tmp"), uids, deleteFileList, 0);
			} catch(FileNotFoundException err) {
				if(logger.isLoggable(Level.FINE)) {
					logger.log(Level.FINE, "This may normally occur because of the dynamic nature of the tmp directories", err);
				}
			}

			// Back-up and delete the files scheduled for removal.
			BackupManager.backupAndDeleteFiles(deleteFileList);

			// Remove shell packages if installed and no longer required
			if(!hasPasswdShell) {
				PackageManager.removePackage(PackageManager.PackageName.AOSERV_PASSWD_SHELL);
			}
			if(!hasFtpShell) {
				PackageManager.removePackage(PackageManager.PackageName.AOSERV_FTP_SHELLS);
			}
		}
	}

	public static String getAutoresponderContent(UnixPath path) throws IOException, SQLException {
		UnixFile file = new UnixFile(path.toString());
		String content;
		if(file.getStat().exists()) {
			StringBuilder SB = new StringBuilder();
			AOServer thisAoServer = AOServDaemon.getThisAOServer();
			try (
				InputStream in = new BufferedInputStream(
					file.getSecureInputStream(
						thisAoServer.getUidMin().getId(),
						thisAoServer.getGidMin().getId()
					)
				)
			) {
				// TODO: This is assuming ISO-8859-1 encoding.  Is this correct here?
				int ch;
				while((ch = in.read()) != -1) SB.append((char)ch);
			}
			content = SB.toString();
		} else {
			content = "";
		}
		return content;
	}

	public static String getCronTable(UserId username) throws IOException, SQLException {
		File cronFile = new File(cronDirectory, username.toString());
		String cronTable;
		if(cronFile.exists()) {
			StringBuilder SB = new StringBuilder();
			try (InputStream in = new BufferedInputStream(new FileInputStream(cronFile))) {
				// TODO: This is assuming ISO-8859-1 encoding.  Is this correct here?
				int ch;
				while((ch = in.read()) != -1) SB.append((char)ch);
			}
			cronTable = SB.toString();
		} else {
			cronTable = "";
		}
		return cronTable;
	}

	/**
	 * @see  ShadowFile#getEncryptedPassword(com.aoindustries.aoserv.client.validator.UserId)
	 */
	public static Tuple2<String,Integer> getEncryptedPassword(UserId username) throws IOException, SQLException {
		return ShadowFile.getEncryptedPassword(username);
	}

	/**
	 * Prints one line of a Linux group file.
	 *
	 * @param  group     the <code>LinuxServerGroup</code> to print
	 * @param  out       the <code>ChainWriter</code> to print to
	 * @param  complete  if <code>true</code>, a complete line will be printed, otherwise
	 *                   only the groupname and gid are included
	 */
	public static void printGroupLine(LinuxServerGroup group, ChainWriter out, Set<UserId> tempSet) throws IOException, SQLException {
		AOServer aoServer = group.getAOServer();
		OperatingSystemVersion osv = aoServer.getServer().getOperatingSystemVersion();
		LinuxGroup linuxGroup = group.getLinuxGroup();
		GroupId groupName = linuxGroup.getName();
		out
			.print(groupName)
			.print(":x:")
			.print(group.getGid().getId())
			.print(':')
		;
		tempSet.clear();
		List<LinuxServerAccount> altAccounts = group.getAlternateLinuxServerAccounts();
		boolean didOne = false;
		int len = altAccounts.size();
		for(int d = 0; d < len; d++) {
			UserId username = altAccounts.get(d)
				.getLinuxAccount()
				.getUsername()
				.getUsername()
			;
			if(!tempSet.add(username)) throw new SQLException("Duplicate alt username: " + username);
			if(didOne) out.print(',');
			else didOne = true;
			out.print(username);
		}
		if(groupName.equals(LinuxGroup.PROFTPD_JAILED)) {
			boolean addJailed;
			int osvId = osv.getPkey();
			if(
				osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
				|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
			) {
				addJailed = true;
			} else if(
				osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
			) {
				addJailed = false;
			} else {
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
			}
			if(addJailed) {
				for(FTPGuestUser guestUser : aoServer.getFTPGuestUsers()) {
					UserId username = guestUser.getLinuxAccount().getUsername().getUsername();
					if(tempSet.add(username)) {
						if(didOne) out.print(',');
						else didOne = true;
						out.print(username);
					}
				}
			}
		}
		out.print('\n');
	}

	public static void printGShadowLine(LinuxServerGroup group, ChainWriter out) throws IOException, SQLException {
		LinuxGroup linuxGroup = group.getLinuxGroup();
		out.print(linuxGroup.getName()).print(":::\n");
	}

	/**
	 * Prints one line of a Linux passwd file.
	 *
	 * @param  account   the <code>LinuxServerAccount</code> to print
	 * @param  out       the <code>ChainWriter</code> to print to
	 */
	public static void printPasswdLine(LinuxServerAccount account, ChainWriter out) throws IOException, SQLException {
		LinuxAccount linuxAccount = account.getLinuxAccount();
		UserId username=linuxAccount.getUsername().getUsername();
		LinuxServerGroup primaryGroup = account.getPrimaryLinuxServerGroup();
		if(primaryGroup == null) throw new SQLException("Unable to find primary LinuxServerGroup for username=" + username + " on " + account.getAOServer().getHostname());
		out
			.print(username)
			.print(":x:")
			.print(account.getUid().getId())
			.print(':')
			.print(primaryGroup.getGid().getId())
			.print(':')
		;
		int commaCount = 0;
		Gecos fullName = linuxAccount.getName();
		if(fullName != null) {
			out.print(fullName.toString());
		}
		Gecos officeLocation = linuxAccount.getOfficeLocation();
		if(officeLocation != null) {
			out.print(',');
			commaCount++;
			out.print(officeLocation.toString());
		}
		Gecos officePhone = linuxAccount.getOfficePhone();
		if(officePhone != null) {
			while(commaCount < 2) {
				out.print(',');
				commaCount++;
			}
			out.print(officePhone.toString());
		}
		Gecos homePhone = linuxAccount.getHomePhone();
		if(homePhone != null) {
			while(commaCount < 3) {
				out.print(',');
				commaCount++;
			}
			out.print(homePhone.toString());
		}
		out
			.print(':')
			.print(account.getHome())
			.print(':')
			.print(linuxAccount.getShell().getPath())
			.print('\n')
		;
	}

	public static void setBashProfile(LinuxServerAccount lsa, String profile) throws IOException, SQLException {
		String profileLine = "[ -f '" + profile + "' ] && . '" + profile + "'";
		String oldProfileLine = ". " + profile;

		UnixFile profileFile = new UnixFile(lsa.getHome().toString(), BASHRC);

		// Make sure the file exists
		if(profileFile.getStat().exists()) {
			AOServer thisAoServer = AOServDaemon.getThisAOServer();
			int uid_min = thisAoServer.getUidMin().getId();
			int gid_min = thisAoServer.getGidMin().getId();

			boolean found = false;
			// Read the old file, looking for the source in the file
			try (BufferedReader in = new BufferedReader(new InputStreamReader(profileFile.getSecureInputStream(uid_min, gid_min)))) {
				String line;
				while((line = in.readLine()) != null) {
					line = line.trim();
					if(
						line.equals(profileLine)
						|| line.equals(oldProfileLine)
					) {
						found = true;
						break;
					}
				}
			}
			if(!found) {
				try (RandomAccessFile out = profileFile.getSecureRandomAccessFile("rw", uid_min, gid_min)) {
					out.seek(out.length());
					out.seek(out.length());
					out.write('\n');
					out.writeBytes(profileLine);
					out.write('\n');
				}
			}
		}
	}

	public static void setAutoresponderContent(UnixPath path, String content, int uid, int gid) throws IOException, SQLException {
		AOServer thisAoServer = AOServDaemon.getThisAOServer();
		int uid_min = thisAoServer.getUidMin().getId();
		int gid_min = thisAoServer.getGidMin().getId();
		File file=new File(path.toString());
		synchronized(rebuildLock) {
			if(content==null) {
				if(file.exists()) FileUtils.delete(file);
			} else {
				try (
					PrintWriter out = new PrintWriter(
						new BufferedOutputStream(
							new UnixFile(file).getSecureOutputStream(uid, gid, 0600, true, uid_min, gid_min)
						)
					)
				) {
					out.print(content);
				}
			}
		}
	}

	public static void setCronTable(UserId username, String cronTable) throws IOException, SQLException {
		AOServer thisAoServer = AOServDaemon.getThisAOServer();
		int uid_min = thisAoServer.getUidMin().getId();
		int gid_min = thisAoServer.getGidMin().getId();
		File cronFile = new File(cronDirectory, username.toString());
		synchronized(rebuildLock) {
			if(cronTable.isEmpty()) {
				if(cronFile.exists()) FileUtils.delete(cronFile);
			} else {
				try (
					PrintWriter out = new PrintWriter(
						new BufferedOutputStream(
							new UnixFile(cronFile).getSecureOutputStream(
								UnixFile.ROOT_UID,
								thisAoServer.getLinuxServerAccount(username).getPrimaryLinuxServerGroup().getGid().getId(),
								0600,
								true,
								uid_min,
								gid_min
							)
						)
					)
				) {
					out.print(cronTable);
				}
			}
		}
	}

	/**
	 * @see  ShadowFile#setEncryptedPassword(com.aoindustries.aoserv.client.validator.UserId, java.lang.String, java.lang.Integer)
	 */
	public static void setEncryptedPassword(UserId username, String encryptedPassword, Integer changedDate) throws IOException, SQLException {
		AOServer aoServer = AOServDaemon.getThisAOServer();
		LinuxServerAccount lsa = aoServer.getLinuxServerAccount(username);
		if(lsa == null) throw new SQLException("Unable to find LinuxServerAccount: " + username + " on " + aoServer);
		ShadowFile.setEncryptedPassword(username, encryptedPassword, changedDate);
	}

	@SuppressWarnings("deprecation")
	public static void setPassword(UserId username, String plain_password, boolean updateChangedDate) throws IOException, SQLException {
		AOServer aoServer = AOServDaemon.getThisAOServer();
		LinuxServerAccount lsa = aoServer.getLinuxServerAccount(username);
		if(lsa == null) throw new SQLException("Unable to find LinuxServerAccount: " + username + " on " + aoServer);
		UnixFile.CryptAlgorithm cryptAlgorithm;
		OperatingSystemVersion osv = aoServer.getServer().getOperatingSystemVersion();
		switch(osv.getPkey()) {
			case OperatingSystemVersion.MANDRIVA_2006_0_I586 :
			case OperatingSystemVersion.REDHAT_ES_4_X86_64 :
			case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 :
				cryptAlgorithm = UnixFile.CryptAlgorithm.MD5;
				break;
			case OperatingSystemVersion.CENTOS_7_X86_64 :
				cryptAlgorithm = UnixFile.CryptAlgorithm.SHA512;
				break;
			default :
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
		}
		ShadowFile.setPassword(username, plain_password, cryptAlgorithm, updateChangedDate);
	}

	private static LinuxAccountManager linuxAccountManager;
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
				&& AOServDaemonConfiguration.isManagerEnabled(LinuxAccountManager.class)
				&& linuxAccountManager == null
			) {
				System.out.print("Starting LinuxAccountManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					// TODO: CentOS 7 once system groups and accounts auto-added
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					linuxAccountManager = new LinuxAccountManager();
					conn.getFtpGuestUsers().addTableListener(linuxAccountManager, 0);
					conn.getLinuxAccounts().addTableListener(linuxAccountManager, 0);
					conn.getLinuxGroupAccounts().addTableListener(linuxAccountManager, 0);
					conn.getLinuxServerAccounts().addTableListener(linuxAccountManager, 0);
					conn.getLinuxServerGroups().addTableListener(linuxAccountManager, 0);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	public static void tarHomeDirectory(CompressedDataOutputStream out, UserId username) throws IOException, SQLException {
		LinuxServerAccount lsa = AOServDaemon.getThisAOServer().getLinuxServerAccount(username);
		UnixPath home = lsa.getHome();
		UnixFile tempUF = UnixFile.mktemp("/tmp/tar_home_directory.tar.", true);
		try {
			AOServDaemon.exec(
				"/bin/tar",
				"-c",
				"-C",
				home.toString(),
				"-f",
				tempUF.getPath(),
				"."
			);
			try (InputStream in = new FileInputStream(tempUF.getFile())) {
				byte[] buff = BufferManager.getBytes();
				try {
					int ret;
					while((ret = in.read(buff, 0, BufferManager.BUFFER_SIZE)) != -1) {
						out.writeByte(AOServDaemonProtocol.NEXT);
						out.writeShort(ret);
						out.write(buff, 0, ret);
					}
				} finally {
					BufferManager.release(buff, false);
				}
			}
		} finally {
			tempUF.delete();
		}
	}

	public static void untarHomeDirectory(CompressedDataInputStream in, UserId username) throws IOException, SQLException {
		AOServer thisAoServer = AOServDaemon.getThisAOServer();
		int uid_min = thisAoServer.getUidMin().getId();
		int gid_min = thisAoServer.getGidMin().getId();
		synchronized(rebuildLock) {
			LinuxServerAccount lsa = thisAoServer.getLinuxServerAccount(username);
			UnixPath home = lsa.getHome();
			UnixFile tempUF = UnixFile.mktemp("/tmp/untar_home_directory.tar.", true);
			try {
				int code;
				try (OutputStream out = tempUF.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true, uid_min, gid_min)) {
					byte[] buff = BufferManager.getBytes();
					try {
						while((code = in.readByte()) == AOServDaemonProtocol.NEXT) {
							int len = in.readShort();
							in.readFully(buff, 0, len);
							out.write(buff, 0, len);
						}
					} finally {
						BufferManager.release(buff, false);
					}
				}
				if(code != AOServDaemonProtocol.DONE) {
					if(code == AOServDaemonProtocol.IO_EXCEPTION) throw new IOException(in.readUTF());
					else if(code == AOServDaemonProtocol.SQL_EXCEPTION) throw new SQLException(in.readUTF());
					else throw new IOException("Unknown result: " + code);
				}
				AOServDaemon.exec(
					"/bin/tar",
					"-x",
					"-C",
					home.toString(),
					"-f",
					tempUF.getPath()
				);
			} finally {
				tempUF.delete();
			}
		}
	}

	public static void waitForRebuild() {
		if(linuxAccountManager != null) linuxAccountManager.waitForBuild();
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild Linux Accounts";
	}

	@Override
	public long getProcessTimerMaximumTime() {
		return 15*60*1000;
	}

	/**
	 * Allows manual rebuild without the necessity of running the entire daemon (use carefully, only when main daemon not running).
	 */
	public static void main(String[] args) {
		try {
			rebuildLinuxAccountSettings();
		} catch(RuntimeException | IOException | SQLException err) {
			ErrorPrinter.printStackTraces(err);
		}
	}

	private LinuxAccountManager() {
	}
}
