/*
 * Copyright 2001-2013, 2014, 2015, 2016, 2017, 2018, 2019 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.unix.linux;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.ftp.GuestUser;
import com.aoindustries.aoserv.client.linux.Group;
import com.aoindustries.aoserv.client.linux.GroupServer;
import com.aoindustries.aoserv.client.linux.LinuxId;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.Shell;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.linux.UserType;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.email.ImapManager;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.GShadowFile;
import com.aoindustries.aoserv.daemon.unix.GroupFile;
import com.aoindustries.aoserv.daemon.unix.PasswdFile;
import com.aoindustries.aoserv.daemon.unix.ShadowFile;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.io.FileUtils;
import com.aoindustries.io.stream.StreamableInput;
import com.aoindustries.io.stream.StreamableOutput;
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
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TODO: Watch for changes in /etc/passwd and /etc/group and auto-run rebuild.
 * TODO: This will more promptly add new system users and groups to the master.
 *
 * @author  AO Industries, Inc.
 */
public class LinuxAccountManager extends BuilderThread {

	private static final Logger logger = Logger.getLogger(LinuxAccountManager.class.getName());

	/**
	 * Lockfile for password file updates.
	 *
	 * TODO: Is this sufficient locking, or should we also lock each individual file while updating? (/etc/passwd.lock, ...)
	 */
	private static final File PWD_LOCK = new File("/etc/.pwd.lock");

	private static final UnixFile SUDOERS_D = new UnixFile("/etc/sudoers.d");

	private static final String BASHRC = ".bashrc";

	private static final File cronDirectory = new File("/var/spool/cron");

	public static boolean comparePassword(User.Name username, String password) throws IOException, SQLException {
		String crypted = ShadowFile.getEncryptedPassword(username).getElement1();
		if(crypted.equals(User.NO_PASSWORD_CONFIG_VALUE)) return false;
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
		} catch(RuntimeException | IOException | SQLException T) {
			logger.log(Level.SEVERE, null, T);
			return false;
		}
	}

	private static void rebuildLinuxAccountSettings() throws IOException, SQLException {
		AOServConnector connector = AOServDaemon.getConnector();
		Server thisServer = AOServDaemon.getThisServer();
		HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();

		OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		if(
			osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
			&& osvId != OperatingSystemVersion.REDHAT_ES_4_X86_64
			&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
			&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
		) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

		int uidMin = thisServer.getUidMin().getId();
		int uidMax = thisServer.getUidMax().getId();
		int gidMin = thisServer.getGidMin().getId();
		int gidMax = thisServer.getGidMax().getId();
		if(logger.isLoggable(Level.FINER)) logger.finer("uidMin=" + uidMin + ", uidMax=" + uidMax + ", gidMin=" + gidMin + ", gidMax=" + gidMax);

		synchronized(rebuildLock) {
			// Get the lists from the database
			List<UserServer> lsas = thisServer.getLinuxServerAccounts();
			boolean hasFtpShell = false;
			boolean hasPasswdShell = false;
			final Set<User.Name> usernames;
			final Set<String> usernameStrs;
			final Set<Integer> uids;
			final Set<String> homeDirs;
			final Map<User.Name,PasswdFile.Entry> passwdEntries;

			List<GroupServer> lsgs = thisServer.getLinuxServerGroups();
			final Map<Group.Name,Set<User.Name>> groups;
			final Map<Group.Name,GroupFile.Entry> groupEntries;

			Set<UnixFile> restorecon = new LinkedHashSet<>();
			try {
				try (
					FileChannel fileChannel = FileChannel.open(PWD_LOCK.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE);
					FileLock fileLock = fileChannel.lock();
				) {
					// Add any system groups found, updating lsgs
					{
						Map<Group.Name,GroupFile.Entry> groupFile;
						synchronized(GroupFile.groupLock) {
							groupFile = GroupFile.readGroupFile();
						}
						boolean modified = false;
						for(GroupFile.Entry entry : groupFile.values()) {
							Group.Name groupName = entry.getGroupName();
							if(
								entry.getGid() < gidMin
								|| entry.getGid() > gidMax
								|| groupName.equals(Group.AOADMIN)
							) {
								boolean found = false;
								for(GroupServer lsg : lsgs) {
									if(lsg.getLinuxGroup().getName().equals(groupName)) {
										found = true;
										break;
									}
								}
								if(!found) {
									int gid = entry.getGid();
									if(logger.isLoggable(Level.FINE)) {
										logger.fine("Adding system group: " + groupName + " #" + gid);
									}
									thisServer.addSystemGroup(groupName, gid);
									modified = true;
								}
							}
						}
						if(modified) lsgs = thisServer.getLinuxServerGroups();
					}
					// Add any system users found, updating lsas
					{
						Map<User.Name,PasswdFile.Entry> passwdFile;
						synchronized(PasswdFile.passwdLock) {
							passwdFile = PasswdFile.readPasswdFile();
						}
						boolean modified = false;
						for(PasswdFile.Entry entry : passwdFile.values()) {
							User.Name username = entry.getUsername();
							if(
								entry.getUid() < uidMin
								|| entry.getUid() > uidMax
								|| username.equals(User.AOADMIN)
							) {
								boolean found = false;
								for(UserServer lsa : lsas) {
									if(lsa.getLinuxAccount().getUsername().getUsername().equals(username)) {
										found = true;
										break;
									}
								}
								if(!found) {
									int uid = entry.getUid();
									if(logger.isLoggable(Level.FINE)) {
										logger.fine("Adding system user: " + username + " #" + uid);
									}
									thisServer.addSystemUser(
										username,
										uid,
										entry.getGid(),
										entry.getFullName(),
										entry.getOfficeLocation(),
										entry.getOfficePhone(),
										entry.getHomePhone(),
										entry.getHome(),
										entry.getShell()
									);
									modified = true;
								}
							}
						}
						if(modified) lsas = thisServer.getLinuxServerAccounts();
					}

					// Install /usr/bin/ftppasswd and /usr/bin/ftponly if required by any UserServer
					for(UserServer lsa : lsas) {
						PosixPath shellPath = lsa.getLinuxAccount().getShell().getPath();
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

					// Add /usr/bin/passwd to /etc/shells if required by any UserServer
					for(UserServer lsa : lsas) {
						if(lsa.getLinuxAccount().getShell().getPath().equals(Shell.PASSWD)) {
							hasPasswdShell = true;
							break;
						}
					}
					if(hasPasswdShell) {
						PackageManager.installPackage(PackageManager.PackageName.AOSERV_PASSWD_SHELL);
					}

					// Build passwd data
					{
						int initialCapacity = lsas.size()*4/3+1;
						usernames     = new LinkedHashSet<>(initialCapacity);
						usernameStrs  = new LinkedHashSet<>(initialCapacity);
						uids          = new LinkedHashSet<>(initialCapacity);
						homeDirs      = new LinkedHashSet<>(initialCapacity);
						passwdEntries = new LinkedHashMap<>(initialCapacity);
						boolean hasRoot = false;
						for(UserServer lsa : lsas) {
							User la = lsa.getLinuxAccount();
							User.Name username = la.getUsername_id();
							if(!usernames.add(username)) throw new SQLException("Duplicate username: " + username);
							if(!usernameStrs.add(username.toString())) throw new AssertionError();
							uids.add(lsa.getUid().getId());
							homeDirs.add(lsa.getHome().toString());
							GroupServer primaryGroup = lsa.getPrimaryLinuxServerGroup();
							if(primaryGroup == null) throw new SQLException("Unable to find primary GroupServer for username=" + username + " on " + lsa.getServer());
							PosixPath shell = la.getShell().getPath();
							// CentOS 5 requires /bin/bash, but CentOS 7 ships with /sbin/nologin.
							// Unfortunately, in our current schema the shell is set of all servers at once.
							// This ugly hack allows us to store the new version, and it will be converted
							// for compatibility with CentOS 5 on-the-fly.
							if(
								osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
								&& username.equals(User.CYRUS)
								&& shell.equals(Shell.NOLOGIN)
							) {
								if(logger.isLoggable(Level.INFO)) {
									logger.info("Converting " + shell + " to " + Shell.BASH + " for " + username);
									shell = Shell.BASH;
								}
							}
							if(
								passwdEntries.put(
									username,
									new PasswdFile.Entry(
										username,
										lsa.getUid().getId(),
										primaryGroup.getGid().getId(),
										la.getName(),
										la.getOfficeLocation(),
										la.getOfficePhone(),
										la.getHomePhone(),
										lsa.getHome(),
										shell
									)
								) != null
							) {
								throw new SQLException("Duplicate username: " + username);
							}
							if(username.equals(User.ROOT)) hasRoot = true;
						}
						if(!hasRoot) throw new SQLException(User.ROOT + " user not found");
					}

					// Build group data
					{
						int initialCapacity = lsgs.size()*4/3+1;
						groups = new LinkedHashMap<>(initialCapacity);
						groupEntries = new LinkedHashMap<>(initialCapacity);
						boolean hasRoot = false;
						for(GroupServer lsg : lsgs) {
							Group.Name groupName = lsg.getLinuxGroup().getName();
							Set<User.Name> groupMembers = new LinkedHashSet<>();
							{
								for(UserServer altAccount : lsg.getAlternateLinuxServerAccounts()) {
									User.Name userId = altAccount.getLinuxAccount_username_id();
									if(!groupMembers.add(userId)) throw new SQLException("Duplicate group member: " + userId);
								}
								if(groupName.equals(Group.PROFTPD_JAILED)) {
									if(
										osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
										|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
									) {
										for(GuestUser guestUser : thisServer.getFTPGuestUsers()) {
											groupMembers.add(guestUser.getLinuxAccount().getUsername_id());
										}
									} else if(
										osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
										|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
									) {
										// Nothing to do, no special FTP server groups
									} else {
										throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
									}
								}
							}
							if(groups.put(groupName, groupMembers) != null) throw new SQLException("Duplicate group name: " + groupName);
							if(
								groupEntries.put(
									groupName,
									new GroupFile.Entry(
										groupName,
										lsg.getGid().getId(),
										groupMembers
									)
								) != null
							) {
								throw new SQLException("Duplicate group name: " + groupName);
							}
							if(groupName.equals(Group.ROOT)) hasRoot = true;
						}
						if(!hasRoot) throw new SQLException(Group.ROOT + " group not found");
					}

					synchronized(PasswdFile.passwdLock) {
						synchronized(ShadowFile.shadowLock) {
							synchronized(GroupFile.groupLock) {
								synchronized(GShadowFile.gshadowLock) {
									// Build new file contents
									byte[] newPasswdContent  = PasswdFile .buildPasswdFile (passwdEntries, uidMin, uidMax);
									byte[] newShadowContent  = ShadowFile .buildShadowFile (usernames);
									byte[] newGroupContent   = GroupFile  .buildGroupFile  (groupEntries, gidMin, gidMax);
									byte[] newGShadowContent = GShadowFile.buildGShadowFile(groups);
									// Write any updates
									PasswdFile .writePasswdFile (newPasswdContent,  restorecon);
									ShadowFile .writeShadowFile (newShadowContent,  restorecon);
									GroupFile  .writeGroupFile  (newGroupContent,   restorecon);
									GShadowFile.writeGShadowFile(newGShadowContent, restorecon);
								}
							}
						}
					}
					// restorecon any changed before releasing lock
					DaemonFileUtils.restorecon(restorecon);
					restorecon.clear();
				}
				// A list of all files to delete is created so that all the data can
				// be backed-up before removal.
				List<File> deleteFileList = new ArrayList<>();

				if(osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586) {
					// Create any inboxes that need to exist.
					GroupServer mailGroup = connector.getLinux().getGroup().get(Group.MAIL).getLinuxServerGroup(thisServer);
					if(mailGroup == null) throw new SQLException("Unable to find GroupServer: " + Group.MAIL + " on " + thisServer.getHostname());
					for(UserServer lsa : lsas) {
						User la = lsa.getLinuxAccount();
						if(la.getType().isEmail()) {
							User.Name username = la.getUsername_id();
							File file = new File(ImapManager.mailSpool, username.toString());
							if(!file.exists()) {
								UnixFile unixFile = new UnixFile(file.getPath());
								if(logger.isLoggable(Level.INFO)) logger.info("Creating inbox: \"" + unixFile + '"');
								unixFile.getSecureOutputStream(
									lsa.getUid().getId(),
									mailGroup.getGid().getId(),
									0660,
									false,
									uidMin,
									gidMin
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
									if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + spoolFile);
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

				// Create any home directories that do not exist.
				for(UserServer lsa : lsas) {
					User la = lsa.getLinuxAccount();
					String type = la.getType().getName();
					User.Name username = la.getUsername_id();
					GroupServer primaryLsg = lsa.getPrimaryLinuxServerGroup();
					int uid = lsa.getUid().getId();
					int gid = primaryLsg.getGid().getId();

					boolean copySkel = false;

					final PosixPath homePath = lsa.getHome();
					final UnixFile homeDir = new UnixFile(homePath.toString());
					if(!homeDir.getStat().exists()) {
						// Make the parent of the home directory if it does not exist
						UnixFile parent = homeDir.getParent();
						if(!parent.getStat().exists()) {
							if(logger.isLoggable(Level.INFO)) logger.info("mkdir \"" + parent + '"');
							parent.mkdir(true, 0755);
						}
						// Look for home directory being moved
						UnixFile oldHome;
						{
							PosixPath defaultHome = UserServer.getDefaultHomeDirectory(username);
							PosixPath hashedHome = UserServer.getHashedHomeDirectory(username);
							if(homePath.equals(defaultHome)) {
								oldHome = new UnixFile(hashedHome.toString());
							} else if(homePath.equals(hashedHome)) {
								oldHome = new UnixFile(defaultHome.toString());
							} else {
								oldHome = null;
							}
						}
						if(
							oldHome != null
							// Don't move a home directory still being used
							&& !homeDirs.contains(oldHome.getPath())
							// Only move when exists
							&& oldHome.getStat().exists()
						) {
							// Move the home directory from old location
							if(logger.isLoggable(Level.INFO)) logger.info("mv \"" + oldHome + "\" \"" + homeDir + '"');
							oldHome.renameTo(homeDir);
							// This moved directory requires restorecon
							restorecon.add(homeDir);
						} else {
							// Make the home directory
							if(logger.isLoggable(Level.INFO)) logger.info("mkdir \"" + homeDir + '"');
							homeDir.mkdir(false, 0700);
							copySkel = true;
						}
					}
					// Set up the directory if it was just created or was created as root before
					final String homeStr = homeDir.getPath();
					// Homes in /www will have all the skel copied, but will not set the directory perms
					boolean isWWWAndUser =
						homeStr.startsWith(osConfig.getHttpdSitesDirectory().toString() + '/')
						&& (type.equals(UserType.USER) || type.equals(UserType.APPLICATION))
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
							&& (type.equals(UserType.USER) || type.equals(UserType.APPLICATION))
						) {
							File skel = new File("/etc/skel");
							String[] skelList = skel.list();
							if(skelList != null) {
								for(String filename : skelList) {
									UnixFile homeFile = new UnixFile(homeDir, filename, true);
									if(!homeFile.getStat().exists()) {
										UnixFile skelFile = new UnixFile(skel, filename);
										if(logger.isLoggable(Level.INFO)) logger.info("cp \"" + skelFile + "\" \"" + homeFile + '"');
										skelFile.copyTo(homeFile, false);
										if(logger.isLoggable(Level.INFO)) logger.info("chown " + uid + ':' + gid + " \"" + homeFile + '"');
										homeFile.chown(uid, gid);
									}
								}
							}
						}
						// Set final directory ownership now that home directory completely setup
						if(chownHome) {
							if(logger.isLoggable(Level.INFO)) logger.info("chown " + uid + ':' + gid + " \"" + homeDir + '"');
							homeDir.chown(uid, gid);
							// Now done in mkdir above: homeDir.setMode(0700);
						}
					}
				}

				// restorecon any moved home directories
				DaemonFileUtils.restorecon(restorecon);
				restorecon.clear();

				/*
				 * Remove any home directories that should not exist.
				 */
				Set<String> keepHashDirs = new HashSet<>();
				for(char ch='a'; ch<='z'; ch++) {
					UnixFile hashDir = new UnixFile("/home/" + ch);
					if(homeDirs.contains(hashDir.getPath())) {
						if(logger.isLoggable(Level.FINE)) logger.fine("hashDir is a home directory, not cleaning: " + hashDir);
					} else if(hashDir.getStat().exists()) {
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
									if(logger.isLoggable(Level.FINE)) logger.fine("hashDir has home directory: " + dir);
									hasHomeDir = true;
								} else {
									if(logger.isLoggable(Level.FINE)) logger.fine("hashDir has an extra directory: " + dir);
									hashDirToDelete.add(dir.getFile());
								}
							}
						}
						if(hasHomeDir) {
							if(logger.isLoggable(Level.FINE)) logger.fine("hashDir still has home directories: " + hashDir);
							for(File toDelete : hashDirToDelete) {
								if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
								deleteFileList.add(toDelete);
							}
							keepHashDirs.add(hashDir.getPath());
						} else {
							if(logger.isLoggable(Level.FINE)) logger.fine("hashDir does not have any home directories, will be deleted completely: " + hashDir);
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
						if(keepHashDirs.contains(dirPath)) {
							if(logger.isLoggable(Level.FINE)) logger.fine("Keeping hashDir that is still used: " + dir);
						} else {
							// Allow encrypted form of home directory
							if(dirPath.endsWith(".aes256.img")) dirPath = dirPath.substring(0, dirPath.length() - ".aes256.img".length());
							if(homeDirs.contains(dirPath)) {
								if(logger.isLoggable(Level.FINE)) logger.fine("Is a home directory: " + dir);
							} else {
								File toDelete = dir.getFile();
								if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
								deleteFileList.add(toDelete);
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
						if(!usernameStrs.contains(filename)) {
							File toDelete = new File(cronDirectory, filename);
							if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
							deleteFileList.add(toDelete);
						}
					}
				}

				// Configure sudo
				if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
					Map<String,String> sudoers = new LinkedHashMap<>();
					for(UserServer lsa : lsas) {
						String sudo = lsa.getSudo();
						if(sudo != null) {
							sudoers.put(lsa.getLinuxAccount().getUsername().getUsername().toString(), sudo);
						}
					}
					if(!sudoers.isEmpty()) {
						// Install package when first needed
						PackageManager.installPackage(PackageManager.PackageName.SUDO);
						// Create /etc/sudoers.d if missing
						if(!SUDOERS_D.getStat().exists()) SUDOERS_D.mkdir(false, 0750);
						// Update any files
						ByteArrayOutputStream bout = new ByteArrayOutputStream();
						for(Map.Entry<String,String> entry : sudoers.entrySet()) {
							String username = entry.getKey();
							String sudo = entry.getValue();
							bout.reset();
							try (Writer out = new OutputStreamWriter(bout, StandardCharsets.UTF_8)) {
								out.write("##\n"
									+ "## Configured by ");
								out.write(LinuxAccountManager.class.getName());
								out.write("\n"
									+ "## \n"
									+ "## See ");
								out.write(UserServer.class.getName());
								out.write(".getSudo()\n"
									+ "##\n");
								out.write(username);
								out.write(' ');
								out.write(sudo);
								out.write('\n');
							}
							DaemonFileUtils.atomicWrite(
								new UnixFile(SUDOERS_D, username, true),
								bout.toByteArray(),
								0440,
								UnixFile.ROOT_UID,
								UnixFile.ROOT_GID,
								null,
								restorecon
							);
						}
					}
					// restorecon any new config files
					DaemonFileUtils.restorecon(restorecon);
					restorecon.clear();
					// Delete any extra files
					String[] list = SUDOERS_D.list();
					if(list != null) {
						for(String filename : list) {
							if(!sudoers.containsKey(filename)) {
								File toDelete = new File(SUDOERS_D.getFile(), filename);
								if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
								deleteFileList.add(toDelete);
							}
						}
					}
				} else if(
					osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				) {
					// Not supporting sudo on these operating system versions
				} else {
					throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
				}

				// Disable and enable accounts
				// TODO: Put "!" in from of the password when disabled, like done for usermod --lock
				// TODO: Then no longer have PredisablePassword stored in the master.
				// TODO: Consider effect on isPasswordSet and comparePassword (should password check still work on disabled user?)
				for(UserServer lsa : lsas) {
					String prePassword = lsa.getPredisablePassword();
					if(lsa.isDisabled()) {
						// Account is disabled
						if(prePassword == null) {
							User.Name username = lsa.getLinuxAccount_username_id();
							if(logger.isLoggable(Level.INFO)) logger.info("Storing predisable password for " + username);
							lsa.setPredisablePassword(getEncryptedPassword(username).getElement1());
							if(logger.isLoggable(Level.INFO)) logger.info("Clearing password for " + username);
							setPassword(username, null, false);
						}
					} else {
						// Account is enabled
						if(prePassword != null) {
							User.Name username = lsa.getLinuxAccount_username_id();
							if(logger.isLoggable(Level.INFO)) logger.info("Restoring password for " + username);
							setEncryptedPassword(username, prePassword, null);
							if(logger.isLoggable(Level.INFO)) logger.info("Clearing predisable password for " + username);
							lsa.setPredisablePassword(null);
						}
					}
				}

				// Only the top level server in a physical server gets to kill processes
				if(AOServDaemonConfiguration.isNested()) {
					if(logger.isLoggable(Level.FINE)) logger.fine("This server is nested, not killing processes.");
				} else if(thisServer.getFailoverServer() != null) {
					if(logger.isLoggable(Level.FINE)) logger.fine("This server is in a fail-over state, not killing processes; parent server will kill processes.");
				} else {
					List<Server> nestedServers = thisServer.getNestedServers();

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
									int pid = Integer.parseInt(filename);
									LinuxProcess process = new LinuxProcess(pid);
									int uid = process.getUid();
									// Never kill root processes, just to be safe
									if(uid != UserServer.ROOT_UID) {
										// Search each server
										UserServer lsa;
										if(
											!uids.contains(uid)
											|| (lsa = thisServer.getLinuxServerAccount(LinuxId.valueOf(uid))) == null
											|| lsa.isDisabled()
										) {
											// Also must not be in a nested server
											boolean foundInNested = false;
											for(Server nestedServer : nestedServers) {
												lsa = nestedServer.getLinuxServerAccount(LinuxId.valueOf(uid));
												if(
													lsa != null
													&& !lsa.isDisabled()
												) {
													foundInNested = true;
													break;
												}
											}
											if(!foundInNested) {
												if(logger.isLoggable(Level.INFO)) logger.info("Killing process # " + pid + " running as user # " + uid);
												process.killProc();
											}
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
					List<File> tmpToDelete = new ArrayList<>();
					AOServDaemon.findUnownedFiles(new File("/tmp"), uids, tmpToDelete, 0);
					AOServDaemon.findUnownedFiles(new File("/var/tmp"), uids, tmpToDelete, 0);
					for(File toDelete : tmpToDelete) {
						if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
						deleteFileList.add(toDelete);
					}
				} catch(FileNotFoundException err) {
					if(logger.isLoggable(Level.FINE)) {
						logger.log(Level.FINE, "This may normally occur because of the dynamic nature of the tmp directories", err);
					}
				}

				// Back-up and delete the files scheduled for removal.
				BackupManager.backupAndDeleteFiles(deleteFileList);

				// Remove shell packages if installed and no longer required
				if(AOServDaemonConfiguration.isPackageManagerUninstallEnabled()) {
					if(!hasPasswdShell) {
						PackageManager.removePackage(PackageManager.PackageName.AOSERV_PASSWD_SHELL);
					}
					if(!hasFtpShell) {
						PackageManager.removePackage(PackageManager.PackageName.AOSERV_FTP_SHELLS);
					}
				}
			} finally {
				DaemonFileUtils.restorecon(restorecon);
			}
		}
	}

	public static String getAutoresponderContent(PosixPath path) throws IOException, SQLException {
		UnixFile file = new UnixFile(path.toString());
		String content;
		if(file.getStat().exists()) {
			StringBuilder SB = new StringBuilder();
			Server thisServer = AOServDaemon.getThisServer();
			try (
				InputStream in = new BufferedInputStream(
					file.getSecureInputStream(
						thisServer.getUidMin().getId(),
						thisServer.getGidMin().getId()
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

	public static String getCronTable(User.Name username) throws IOException, SQLException {
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
	 * @see  ShadowFile#getEncryptedPassword(com.aoindustries.aoserv.client.validator.User.Name)
	 */
	public static Tuple2<String,Integer> getEncryptedPassword(User.Name username) throws IOException, SQLException {
		return ShadowFile.getEncryptedPassword(username);
	}

	public static void setBashProfile(UserServer lsa, String profile) throws IOException, SQLException {
		String profileLine = "[ -f '" + profile + "' ] && . '" + profile + "'";
		String oldProfileLine = ". " + profile;

		UnixFile profileFile = new UnixFile(lsa.getHome().toString(), BASHRC);

		// Make sure the file exists
		if(profileFile.getStat().exists()) {
			Server thisServer = AOServDaemon.getThisServer();
			int uid_min = thisServer.getUidMin().getId();
			int gid_min = thisServer.getGidMin().getId();

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

	public static void setAutoresponderContent(PosixPath path, String content, int uid, int gid) throws IOException, SQLException {
		Server thisServer = AOServDaemon.getThisServer();
		int uid_min = thisServer.getUidMin().getId();
		int gid_min = thisServer.getGidMin().getId();
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

	public static void setCronTable(User.Name username, String cronTable) throws IOException, SQLException {
		Server thisServer = AOServDaemon.getThisServer();
		int uid_min = thisServer.getUidMin().getId();
		int gid_min = thisServer.getGidMin().getId();
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
								thisServer.getLinuxServerAccount(username).getPrimaryLinuxServerGroup().getGid().getId(),
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
	 * @see  ShadowFile#setEncryptedPassword(com.aoindustries.aoserv.client.validator.User.Name, java.lang.String, java.lang.Integer)
	 */
	public static void setEncryptedPassword(User.Name username, String encryptedPassword, Integer changedDate) throws IOException, SQLException {
		Server linuxServer = AOServDaemon.getThisServer();
		UserServer lsa = linuxServer.getLinuxServerAccount(username);
		if(lsa == null) throw new SQLException("Unable to find UserServer: " + username + " on " + linuxServer);
		ShadowFile.setEncryptedPassword(username, encryptedPassword, changedDate);
	}

	@SuppressWarnings("deprecation")
	public static void setPassword(User.Name username, String plain_password, boolean updateChangedDate) throws IOException, SQLException {
		Server linuxServer = AOServDaemon.getThisServer();
		UserServer lsa = linuxServer.getLinuxServerAccount(username);
		if(lsa == null) throw new SQLException("Unable to find UserServer: " + username + " on " + linuxServer);
		UnixFile.CryptAlgorithm cryptAlgorithm;
		OperatingSystemVersion osv = linuxServer.getHost().getOperatingSystemVersion();
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
				&& AOServDaemonConfiguration.isManagerEnabled(LinuxAccountManager.class)
				&& linuxAccountManager == null
			) {
				System.out.print("Starting LinuxAccountManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					linuxAccountManager = new LinuxAccountManager();
					conn.getFtp().getGuestUser().addTableListener(linuxAccountManager, 0);
					conn.getLinux().getUser().addTableListener(linuxAccountManager, 0);
					conn.getLinux().getGroupUser().addTableListener(linuxAccountManager, 0);
					conn.getLinux().getUserServer().addTableListener(linuxAccountManager, 0);
					conn.getLinux().getGroupServer().addTableListener(linuxAccountManager, 0);

					// TODO: This seemed to not pick-up correctly.  Add a delay?
					PackageManager.addPackageListener(linuxAccountManager); // React to users and groups added by RPMs

					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	public static void tarHomeDirectory(StreamableOutput out, User.Name username) throws IOException, SQLException {
		UserServer lsa = AOServDaemon.getThisServer().getLinuxServerAccount(username);
		PosixPath home = lsa.getHome();
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

	public static void untarHomeDirectory(StreamableInput in, User.Name username) throws IOException, SQLException {
		Server thisServer = AOServDaemon.getThisServer();
		int uid_min = thisServer.getUidMin().getId();
		int gid_min = thisServer.getGidMin().getId();
		synchronized(rebuildLock) {
			UserServer lsa = thisServer.getLinuxServerAccount(username);
			PosixPath home = lsa.getHome();
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
