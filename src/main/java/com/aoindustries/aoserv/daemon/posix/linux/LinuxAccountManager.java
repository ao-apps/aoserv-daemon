/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2001-2013, 2014, 2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.posix.linux;

import com.aoapps.collections.AoCollections;
import com.aoapps.hodgepodge.io.stream.StreamableInput;
import com.aoapps.hodgepodge.io.stream.StreamableOutput;
import com.aoapps.hodgepodge.util.Tuple2;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.io.posix.Stat;
import com.aoapps.lang.SysExits;
import com.aoapps.lang.util.BufferManager;
import com.aoapps.lang.util.ErrorPrinter;
import com.aoapps.lang.validation.ValidationException;
import com.aoapps.tempfiles.TempFile;
import com.aoapps.tempfiles.TempFileContext;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
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
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.posix.GShadowFile;
import com.aoindustries.aoserv.daemon.posix.GroupFile;
import com.aoindustries.aoserv.daemon.posix.PasswdFile;
import com.aoindustries.aoserv.daemon.posix.ShadowFile;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
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
import java.nio.file.Files;
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
public final class LinuxAccountManager extends BuilderThread {

  private static final Logger logger = Logger.getLogger(LinuxAccountManager.class.getName());

  /**
   * Lockfile for password file updates.
   *
   * TODO: Is this sufficient locking, or should we also lock each individual file while updating? (/etc/passwd.lock, ...)
   */
  private static final File PWD_LOCK = new File("/etc/.pwd.lock");

  private static final PosixFile SUDOERS_D = new PosixFile("/etc/sudoers.d");

  private static final String BASHRC = ".bashrc";

  private static final File cronDirectory = new File("/var/spool/cron");

  public static boolean comparePassword(User.Name username, String password) throws IOException, SQLException {
    String crypted = ShadowFile.getEncryptedPassword(username).getElement1();
    if (crypted.equals(User.NO_PASSWORD_CONFIG_VALUE)) {
      return false;
    }
    int len = crypted.length();
    if (len < 2) {
      return false;
    }
    String salt;
    if (crypted.charAt(0) == '$') {
      // Select salt up to but not including the last '$'
      int lastPos = crypted.lastIndexOf('$');
      salt = crypted.substring(0, lastPos);
      if (salt.length() < 3) {
        return false;
      }
    } else {
      // Assume old-school DES
      salt = crypted.substring(0, 2);
    }
    String newCrypted = PosixFile.crypt(password, salt);
    return crypted.equals(newCrypted);
  }

  private static final Object rebuildLock = new Object();
  @Override
  @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
  protected boolean doRebuild() {
    try {
      rebuildLinuxAccountSettings();
      return true;
    } catch (ThreadDeath td) {
      throw td;
    } catch (Throwable t) {
      logger.log(Level.SEVERE, null, t);
      return false;
    }
  }

  @SuppressWarnings("try")
  private static void rebuildLinuxAccountSettings() throws IOException, SQLException {
    Server thisServer = AOServDaemon.getThisServer();
    HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();

    OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
    int osvId = osv.getPkey();
    if (
      osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
      && osvId != OperatingSystemVersion.CENTOS_7_X86_64
    ) {
      throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
    }

    int uidMin = thisServer.getUidMin().getId();
    int uidMax = thisServer.getUidMax().getId();
    int gidMin = thisServer.getGidMin().getId();
    int gidMax = thisServer.getGidMax().getId();
    if (logger.isLoggable(Level.FINER)) {
      logger.finer("uidMin=" + uidMin + ", uidMax=" + uidMax + ", gidMin=" + gidMin + ", gidMax=" + gidMax);
    }

    synchronized (rebuildLock) {
      // Get the lists from the database
      List<UserServer> lsas = thisServer.getLinuxServerAccounts();
      boolean hasFtpShell = false;
      boolean hasPasswdShell = false;
      final Set<User.Name> usernames;
      final Set<String> usernameStrs;
      final Set<Integer> uids;
      final Set<String> homeDirs;
      final Map<User.Name, PasswdFile.Entry> passwdEntries;

      List<GroupServer> lsgs = thisServer.getLinuxServerGroups();
      final Map<Group.Name, Set<User.Name>> groups;
      final Map<Group.Name, GroupFile.Entry> groupEntries;

      Set<PosixFile> restorecon = new LinkedHashSet<>();
      try {
        try (
          FileChannel fileChannel = FileChannel.open(PWD_LOCK.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE);
          FileLock fileLock = fileChannel.lock();
        ) {
          // Add any system groups found, updating lsgs
          {
            Map<Group.Name, GroupFile.Entry> groupFile;
            synchronized (GroupFile.groupLock) {
              groupFile = GroupFile.readGroupFile();
            }
            boolean modified = false;
            for (GroupFile.Entry entry : groupFile.values()) {
              Group.Name groupName = entry.getGroupName();
              if (
                entry.getGid() < gidMin
                || entry.getGid() > gidMax
                || groupName.equals(Group.AOADMIN)
                // AOServ Schema:
                || groupName.equals(Group.ACCOUNTING)
                || groupName.equals(Group.BILLING)
                || groupName.equals(Group.DISTRIBUTION)
                || groupName.equals(Group.INFRASTRUCTURE)
                || groupName.equals(Group.MANAGEMENT)
                || groupName.equals(Group.MONITORING)
                || groupName.equals(Group.RESELLER)
                // Amazon EC2 cloud-init
                || groupName.equals(Group.CENTOS)
              ) {
                boolean found = false;
                for (GroupServer lsg : lsgs) {
                  if (lsg.getLinuxGroup().getName().equals(groupName)) {
                    found = true;
                    break;
                  }
                }
                if (!found) {
                  int gid = entry.getGid();
                  if (logger.isLoggable(Level.FINE)) {
                    logger.fine("Adding system group: " + groupName + " #" + gid);
                  }
                  thisServer.addSystemGroup(groupName, gid);
                  modified = true;
                }
              }
            }
            if (modified) {
              lsgs = thisServer.getLinuxServerGroups();
            }
          }
          // Add any system users found, updating lsas
          {
            Map<User.Name, PasswdFile.Entry> passwdFile;
            synchronized (PasswdFile.passwdLock) {
              passwdFile = PasswdFile.readPasswdFile();
            }
            boolean modified = false;
            for (PasswdFile.Entry entry : passwdFile.values()) {
              User.Name username = entry.getUsername();
              if (
                entry.getUid() < uidMin
                || entry.getUid() > uidMax
                || username.equals(User.AOADMIN)
                // AOServ Schema:
                || username.equals(User.ACCOUNTING)
                || username.equals(User.BILLING)
                || username.equals(User.DISTRIBUTION)
                || username.equals(User.INFRASTRUCTURE)
                || username.equals(User.MANAGEMENT)
                || username.equals(User.MONITORING)
                || username.equals(User.RESELLER)
                // Amazon EC2 cloud-init
                || username.equals(User.CENTOS)
              ) {
                boolean found = false;
                for (UserServer lsa : lsas) {
                  if (lsa.getLinuxAccount().getUsername().getUsername().equals(username)) {
                    found = true;
                    break;
                  }
                }
                if (!found) {
                  int uid = entry.getUid();
                  if (logger.isLoggable(Level.FINE)) {
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
            if (modified) {
              lsas = thisServer.getLinuxServerAccounts();
            }
          }

          // Install /usr/bin/ftppasswd and /usr/bin/ftponly if required by any UserServer
          for (UserServer lsa : lsas) {
            PosixPath shellPath = lsa.getLinuxAccount().getShell().getPath();
            if (
              shellPath.equals(Shell.FTPONLY)
              || shellPath.equals(Shell.FTPPASSWD)
            ) {
              hasFtpShell = true;
              break;
            }
          }
          if (hasFtpShell) {
            PackageManager.installPackage(PackageManager.PackageName.AOSERV_FTP_SHELLS);
          }

          // Add /usr/bin/passwd to /etc/shells if required by any UserServer
          for (UserServer lsa : lsas) {
            if (lsa.getLinuxAccount().getShell().getPath().equals(Shell.PASSWD)) {
              hasPasswdShell = true;
              break;
            }
          }
          if (hasPasswdShell) {
            PackageManager.installPackage(PackageManager.PackageName.AOSERV_PASSWD_SHELL);
          }

          // Build passwd data
          {
            int size = lsas.size();
            usernames     = AoCollections.newLinkedHashSet(size);
            usernameStrs  = AoCollections.newLinkedHashSet(size);
            uids          = AoCollections.newLinkedHashSet(size);
            homeDirs      = AoCollections.newLinkedHashSet(size);
            passwdEntries = AoCollections.newLinkedHashMap(size);
            boolean hasRoot = false;
            for (UserServer lsa : lsas) {
              User la = lsa.getLinuxAccount();
              User.Name username = la.getUsername_id();
              if (!usernames.add(username)) {
                throw new SQLException("Duplicate username: " + username);
              }
              if (!usernameStrs.add(username.toString())) {
                throw new AssertionError();
              }
              uids.add(lsa.getUid().getId());
              PosixPath home = lsa.getHome();
              homeDirs.add(home.toString());
              GroupServer primaryGroup = lsa.getPrimaryLinuxServerGroup();
              if (primaryGroup == null) {
                throw new SQLException("Unable to find primary GroupServer for username=" + username + " on " + lsa.getServer());
              }
              PosixPath shell = la.getShell().getPath();
              // CentOS 5 requires /bin/bash, but CentOS 7 ships with /sbin/nologin.
              // Unfortunately, in our current schema the shell is set of all servers at once.
              // This ugly hack allows us to store the new version, and it will be converted
              // for compatibility with CentOS 5 on-the-fly.
              if (
                osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                && username.equals(User.CYRUS)
                && shell.equals(Shell.NOLOGIN)
              ) {
                if (logger.isLoggable(Level.INFO)) {
                  logger.info("Converting " + shell + " to " + Shell.BASH + " for " + username);
                  shell = Shell.BASH;
                }
//              } else if (
//                username.equals(User.JENKINS)
//                && home.toString().equals("/home/jenkins")
//              ) {
//                // TODO: Remove this once JCA's Jenkins moved to newer version on separate virtual server:
//                if (logger.isLoggable(Level.INFO)) {
//                  logger.info("Converting " + shell + " to " + Shell.BASH + " for " + username + " to be compatible with previous Jenkins installations in " + home);
//                  shell = Shell.BASH;
//                }
              }
              if (
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
                    home,
                    shell
                  )
                ) != null
              ) {
                throw new SQLException("Duplicate username: " + username);
              }
              if (username.equals(User.ROOT)) {
                hasRoot = true;
              }
            }
            if (!hasRoot) {
              throw new SQLException(User.ROOT + " user not found");
            }
          }

          // Build group data
          {
            int size = lsgs.size();
            groups = AoCollections.newLinkedHashMap(size);
            groupEntries = AoCollections.newLinkedHashMap(size);
            boolean hasRoot = false;
            for (GroupServer lsg : lsgs) {
              Group.Name groupName = lsg.getLinuxGroup().getName();
              Set<User.Name> groupMembers = new LinkedHashSet<>();
              {
                for (UserServer altAccount : lsg.getAlternateLinuxServerAccounts()) {
                  User.Name userId = altAccount.getLinuxAccount_username_id();
                  if (!groupMembers.add(userId)) {
                    throw new SQLException("Duplicate group member: " + userId);
                  }
                }
              }
              if (groups.put(groupName, groupMembers) != null) {
                throw new SQLException("Duplicate group name: " + groupName);
              }
              if (
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
              if (groupName.equals(Group.ROOT)) {
                hasRoot = true;
              }
            }
            if (!hasRoot) {
              throw new SQLException(Group.ROOT + " group not found");
            }
          }

          synchronized (PasswdFile.passwdLock) {
            synchronized (ShadowFile.shadowLock) {
              synchronized (GroupFile.groupLock) {
                synchronized (GShadowFile.gshadowLock) {
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

        // Create any home directories that do not exist.
        for (UserServer lsa : lsas) {
          User la = lsa.getLinuxAccount();
          String type = la.getType().getName();
          User.Name username = la.getUsername_id();
          GroupServer primaryLsg = lsa.getPrimaryLinuxServerGroup();
          int uid = lsa.getUid().getId();
          int gid = primaryLsg.getGid().getId();

          boolean copySkel = false;

          final PosixPath homePath = lsa.getHome();
          final PosixFile homeDir = new PosixFile(homePath.toString());
          if (!homeDir.getStat().exists()) {
            // Make the parent of the home directory if it does not exist
            PosixFile parent = homeDir.getParent();
            if (!parent.getStat().exists()) {
              if (logger.isLoggable(Level.INFO)) {
                logger.info("mkdir \"" + parent + '"');
              }
              parent.mkdir(true, 0755);
            }
            // Look for home directory being moved
            PosixFile oldHome;
            {
              PosixPath defaultHome = UserServer.getDefaultHomeDirectory(username);
              PosixPath hashedHome = UserServer.getHashedHomeDirectory(username);
              if (homePath.equals(defaultHome)) {
                oldHome = new PosixFile(hashedHome.toString());
              } else if (homePath.equals(hashedHome)) {
                oldHome = new PosixFile(defaultHome.toString());
              } else {
                oldHome = null;
              }
            }
            if (
              oldHome != null
              // Don't move a home directory still being used
              && !homeDirs.contains(oldHome.getPath())
              // Only move when exists
              && oldHome.getStat().exists()
            ) {
              // Move the home directory from old location
              if (logger.isLoggable(Level.INFO)) {
                logger.info("mv \"" + oldHome + "\" \"" + homeDir + '"');
              }
              oldHome.renameTo(homeDir);
              // This moved directory requires restorecon
              restorecon.add(homeDir);
            } else {
              // Make the home directory
              if (logger.isLoggable(Level.INFO)) {
                logger.info("mkdir \"" + homeDir + '"');
              }
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
          if (
            isWWWAndUser
            || homeStr.startsWith("/home/")
          ) {
            boolean chownHome;
            {
              Stat homeDirStat = homeDir.getStat();
              chownHome = (
                !isWWWAndUser
                && (
                  homeDirStat.getUid() == PosixFile.ROOT_UID
                  || homeDirStat.getGid() == PosixFile.ROOT_GID
                )
                // Do not set permissions for encrypted home directories
                && !(new PosixFile(homeStr + ".aes256.img").getStat().exists())
              );
              if (chownHome) {
                copySkel = true;
              }
            }
            // Copy the /etc/skel directory
            if (
              copySkel
              // Only copy the files for user accounts
              && (type.equals(UserType.USER) || type.equals(UserType.APPLICATION))
            ) {
              File skel = new File("/etc/skel");
              String[] skelList = skel.list();
              if (skelList != null) {
                for (String filename : skelList) {
                  PosixFile homeFile = new PosixFile(homeDir, filename, true);
                  if (!homeFile.getStat().exists()) {
                    PosixFile skelFile = new PosixFile(skel, filename);
                    if (logger.isLoggable(Level.INFO)) {
                      logger.info("cp \"" + skelFile + "\" \"" + homeFile + '"');
                    }
                    skelFile.copyTo(homeFile, false);
                    if (logger.isLoggable(Level.INFO)) {
                      logger.info("chown " + uid + ':' + gid + " \"" + homeFile + '"');
                    }
                    homeFile.chown(uid, gid);
                  }
                }
              }
            }
            // Set final directory ownership now that home directory completely setup
            if (chownHome) {
              if (logger.isLoggable(Level.INFO)) {
                logger.info("chown " + uid + ':' + gid + " \"" + homeDir + '"');
              }
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
        for (char ch='a'; ch <= 'z'; ch++) {
          PosixFile hashDir = new PosixFile("/home/" + ch);
          if (homeDirs.contains(hashDir.getPath())) {
            if (logger.isLoggable(Level.FINE)) {
              logger.fine("hashDir is a home directory, not cleaning: " + hashDir);
            }
          } else if (hashDir.getStat().exists()) {
            boolean hasHomeDir = false;
            List<File> hashDirToDelete = new ArrayList<>();
            String[] homeList = hashDir.list();
            if (homeList != null) {
              for (String dirName : homeList) {
                PosixFile dir = new PosixFile(hashDir, dirName, true);
                String dirPath = dir.getPath();
                // Allow encrypted form of home directory
                if (dirPath.endsWith(".aes256.img")) {
                  dirPath = dirPath.substring(0, dirPath.length() - ".aes256.img".length());
                }
                if (homeDirs.contains(dirPath)) {
                  if (logger.isLoggable(Level.FINE)) {
                    logger.fine("hashDir has home directory: " + dir);
                  }
                  hasHomeDir = true;
                } else {
                  if (logger.isLoggable(Level.FINE)) {
                    logger.fine("hashDir has an extra directory: " + dir);
                  }
                  hashDirToDelete.add(dir.getFile());
                }
              }
            }
            if (hasHomeDir) {
              if (logger.isLoggable(Level.FINE)) {
                logger.fine("hashDir still has home directories: " + hashDir);
              }
              for (File toDelete : hashDirToDelete) {
                if (logger.isLoggable(Level.INFO)) {
                  logger.info("Scheduling for removal: " + toDelete);
                }
                deleteFileList.add(toDelete);
              }
              keepHashDirs.add(hashDir.getPath());
            } else {
              if (logger.isLoggable(Level.FINE)) {
                logger.fine("hashDir does not have any home directories, will be deleted completely: " + hashDir);
              }
            }
          }
        }
        // Direct children of /home
        PosixFile homeDir = new PosixFile("/home");
        String[] homeList = homeDir.list();
        if (homeList != null) {
          for (String dirName : homeList) {
            PosixFile dir = new PosixFile(homeDir, dirName, true);
            String dirPath = dir.getPath();
            if (keepHashDirs.contains(dirPath)) {
              if (logger.isLoggable(Level.FINE)) {
                logger.fine("Keeping hashDir that is still used: " + dir);
              }
            } else {
              // Allow encrypted form of home directory
              if (dirPath.endsWith(".aes256.img")) {
                dirPath = dirPath.substring(0, dirPath.length() - ".aes256.img".length());
              }
              if (homeDirs.contains(dirPath)) {
                if (logger.isLoggable(Level.FINE)) {
                  logger.fine("Is a home directory: " + dir);
                }
              } else {
                File toDelete = dir.getFile();
                if (logger.isLoggable(Level.INFO)) {
                  logger.info("Scheduling for removal: " + toDelete);
                }
                deleteFileList.add(toDelete);
              }
            }
          }
        }

        /*
         * Remove any cron jobs that should not exist.
         */
        String[] cronList = cronDirectory.list();
        if (cronList != null) {
          for (String filename : cronList) {
            // Filename must be the username of one of the users to be kept intact
            if (!usernameStrs.contains(filename)) {
              File toDelete = new File(cronDirectory, filename);
              if (logger.isLoggable(Level.INFO)) {
                logger.info("Scheduling for removal: " + toDelete);
              }
              deleteFileList.add(toDelete);
            }
          }
        }

        // Configure sudo
        if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
          Map<String, String> sudoers = new LinkedHashMap<>();
          for (UserServer lsa : lsas) {
            String sudo = lsa.getSudo();
            if (sudo != null) {
              sudoers.put(lsa.getLinuxAccount().getUsername().getUsername().toString(), sudo);
            }
          }
          Set<String> sudoersFiles = AoCollections.newHashSet(sudoers.size()); // Filenames might not match username when added by a package
          if (!sudoers.isEmpty()) {
            // Install package when first needed
            PackageManager.installPackage(PackageManager.PackageName.SUDO);
            // Create /etc/sudoers.d if missing
            if (!SUDOERS_D.getStat().exists()) {
              SUDOERS_D.mkdir(false, 0750);
            }
            // Update any files
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            for (Map.Entry<String, String> entry : sudoers.entrySet()) {
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
              String sudoersFilename;
              if (
                // Amazon EC2 cloud-init
                username.equals(User.CENTOS.toString())
                && PackageManager.getInstalledPackage(PackageManager.PackageName.CLOUD_INIT) != null
              ) {
                // Overwrite the file that is created by the "cloud-init" package on boot
                sudoersFilename = "90-cloud-init-users";
              } else {
                sudoersFilename = username;
              }
              DaemonFileUtils.atomicWrite(
                new PosixFile(SUDOERS_D, sudoersFilename, true),
                bout.toByteArray(),
                0440,
                PosixFile.ROOT_UID,
                PosixFile.ROOT_GID,
                null,
                restorecon
              );
              sudoersFiles.add(sudoersFilename);
            }
          }
          // restorecon any new config files
          DaemonFileUtils.restorecon(restorecon);
          restorecon.clear();
          // Delete any extra files
          String[] list = SUDOERS_D.list();
          if (list != null) {
            for (String filename : list) {
              if (!sudoersFiles.contains(filename)) {
                File toDelete = new File(SUDOERS_D.getFile(), filename);
                if (logger.isLoggable(Level.INFO)) {
                  logger.info("Scheduling for removal: " + toDelete);
                }
                deleteFileList.add(toDelete);
              }
            }
          }
        } else if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
          // Not supporting sudo on these operating system versions
        } else {
          throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
        }

        // Disable and enable accounts
        // TODO: Put "!" in from of the password when disabled, like done for usermod --lock
        // TODO: Then no longer have PredisablePassword stored in the master.
        // TODO: Consider effect on isPasswordSet and comparePassword (should password check still work on disabled user?)
        for (UserServer lsa : lsas) {
          String prePassword = lsa.getPredisablePassword();
          if (lsa.isDisabled()) {
            // Account is disabled
            if (prePassword == null) {
              User.Name username = lsa.getLinuxAccount_username_id();
              if (logger.isLoggable(Level.INFO)) {
                logger.info("Storing predisable password for " + username);
              }
              lsa.setPredisablePassword(getEncryptedPassword(username).getElement1());
              if (logger.isLoggable(Level.INFO)) {
                logger.info("Clearing password for " + username);
              }
              setPassword(username, null, false);
            }
          } else {
            // Account is enabled
            if (prePassword != null) {
              User.Name username = lsa.getLinuxAccount_username_id();
              if (logger.isLoggable(Level.INFO)) {
                logger.info("Restoring password for " + username);
              }
              setEncryptedPassword(username, prePassword, null);
              if (logger.isLoggable(Level.INFO)) {
                logger.info("Clearing predisable password for " + username);
              }
              lsa.setPredisablePassword(null);
            }
          }
        }

        // Only the top level server in a physical server gets to kill processes
        if (AOServDaemonConfiguration.isNested()) {
          if (logger.isLoggable(Level.FINE)) {
            logger.fine("This server is nested, not killing processes.");
          }
        } else if (thisServer.getFailoverServer() != null) {
          if (logger.isLoggable(Level.FINE)) {
            logger.fine("This server is in a fail-over state, not killing processes; parent server will kill processes.");
          }
        } else {
          List<Server> nestedServers = thisServer.getNestedServers();

          /*
           * Kill any processes that are running as a UID that
           * should not exist on this server.
           */
          File procDir = new File("/proc");
          String[] procList = procDir.list();
          if (procList != null) {
            for (String filename : procList) {
              int flen = filename.length();
              boolean allNum = true;
              for (int d = 0; d < flen; d++) {
                char ch = filename.charAt(d);
                if (ch<'0' || ch>'9') {
                  allNum = false;
                  break;
                }
              }
              if (allNum) {
                try {
                  int pid = Integer.parseInt(filename);
                  LinuxProcess process = new LinuxProcess(pid);
                  int uid = process.getUid();
                  // Never kill root processes, just to be safe
                  if (uid != UserServer.ROOT_UID) {
                    // Search each server
                    UserServer lsa;
                    if (
                      !uids.contains(uid)
                      || (lsa = thisServer.getLinuxServerAccount(LinuxId.valueOf(uid))) == null
                      || lsa.isDisabled()
                    ) {
                      // Also must not be in a nested server
                      boolean foundInNested = false;
                      for (Server nestedServer : nestedServers) {
                        lsa = nestedServer.getLinuxServerAccount(LinuxId.valueOf(uid));
                        if (
                          lsa != null
                          && !lsa.isDisabled()
                        ) {
                          foundInNested = true;
                          break;
                        }
                      }
                      if (!foundInNested) {
                        if (logger.isLoggable(Level.INFO)) {
                          logger.info("Killing process # " + pid + " running as user # " + uid);
                        }
                        process.killProc();
                      }
                    }
                  }
                } catch (FileNotFoundException err) {
                  if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE, "It is normal that this is thrown if the process has already closed", err);
                  }
                } catch (IOException | ValidationException err) {
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
          for (File toDelete : tmpToDelete) {
            if (logger.isLoggable(Level.INFO)) {
              logger.info("Scheduling for removal: " + toDelete);
            }
            deleteFileList.add(toDelete);
          }
        } catch (FileNotFoundException err) {
          if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, "This may normally occur because of the dynamic nature of the tmp directories", err);
          }
        }

        // Back-up and delete the files scheduled for removal.
        BackupManager.backupAndDeleteFiles(deleteFileList);

        // Remove shell packages if installed and no longer required
        if (AOServDaemonConfiguration.isPackageManagerUninstallEnabled()) {
          if (!hasPasswdShell) {
            PackageManager.removePackage(PackageManager.PackageName.AOSERV_PASSWD_SHELL);
          }
          if (!hasFtpShell) {
            PackageManager.removePackage(PackageManager.PackageName.AOSERV_FTP_SHELLS);
          }
        }
      } catch (InterruptedException e) {
        logger.log(Level.WARNING, null, e);
        // Restore the interrupted status
        Thread.currentThread().interrupt();
      } finally {
        DaemonFileUtils.restorecon(restorecon);
      }
    }
  }

  public static String getAutoresponderContent(PosixPath path) throws IOException, SQLException {
    PosixFile file = new PosixFile(path.toString());
    String content;
    if (file.getStat().exists()) {
      StringBuilder sb = new StringBuilder();
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
        while ((ch = in.read()) != -1) {
          sb.append((char)ch);
        }
      }
      content = sb.toString();
    } else {
      content = "";
    }
    return content;
  }

  public static String getCronTable(User.Name username) throws IOException {
    File cronFile = new File(cronDirectory, username.toString());
    String cronTable;
    if (cronFile.exists()) {
      StringBuilder sb = new StringBuilder();
      try (InputStream in = new BufferedInputStream(new FileInputStream(cronFile))) {
        // TODO: This is assuming ISO-8859-1 encoding.  Is this correct here?
        int ch;
        while ((ch = in.read()) != -1) {
          sb.append((char)ch);
        }
      }
      cronTable = sb.toString();
    } else {
      cronTable = "";
    }
    return cronTable;
  }

  /**
   * @see  ShadowFile#getEncryptedPassword(com.aoindustries.aoserv.client.validator.User.Name)
   */
  public static Tuple2<String, Integer> getEncryptedPassword(User.Name username) throws IOException, SQLException {
    return ShadowFile.getEncryptedPassword(username);
  }

  public static void setBashProfile(UserServer lsa, String profile) throws IOException, SQLException {
    String profileLine = "[ -f '" + profile + "' ] && . '" + profile + "'";
    String oldProfileLine = ". " + profile;

    PosixFile profileFile = new PosixFile(lsa.getHome().toString(), BASHRC);

    // Make sure the file exists
    if (profileFile.getStat().exists()) {
      Server thisServer = AOServDaemon.getThisServer();
      int uid_min = thisServer.getUidMin().getId();
      int gid_min = thisServer.getGidMin().getId();

      boolean found = false;
      // Read the old file, looking for the source in the file
      try (BufferedReader in = new BufferedReader(new InputStreamReader(profileFile.getSecureInputStream(uid_min, gid_min)))) {
        String line;
        while ((line = in.readLine()) != null) {
          line = line.trim();
          if (
            line.equals(profileLine)
            || line.equals(oldProfileLine)
          ) {
            found = true;
            break;
          }
        }
      }
      if (!found) {
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
    synchronized (rebuildLock) {
      if (content == null) {
        if (file.exists()) {
          Files.delete(file.toPath());
        }
      } else {
        try (
          PrintWriter out = new PrintWriter(
            new BufferedOutputStream(
              new PosixFile(file).getSecureOutputStream(uid, gid, 0600, true, uid_min, gid_min)
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
    synchronized (rebuildLock) {
      if (cronTable.isEmpty()) {
        if (cronFile.exists()) {
          Files.delete(cronFile.toPath());
        }
      } else {
        try (
          PrintWriter out = new PrintWriter(
            new BufferedOutputStream(
              new PosixFile(cronFile).getSecureOutputStream(
                PosixFile.ROOT_UID,
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
    if (lsa == null) {
      throw new SQLException("Unable to find UserServer: " + username + " on " + linuxServer);
    }
    ShadowFile.setEncryptedPassword(username, encryptedPassword, changedDate);
  }

  @SuppressWarnings("deprecation")
  public static void setPassword(User.Name username, String plain_password, boolean updateChangedDate) throws IOException, SQLException {
    Server linuxServer = AOServDaemon.getThisServer();
    UserServer lsa = linuxServer.getLinuxServerAccount(username);
    if (lsa == null) {
      throw new SQLException("Unable to find UserServer: " + username + " on " + linuxServer);
    }
    PosixFile.CryptAlgorithm cryptAlgorithm;
    OperatingSystemVersion osv = linuxServer.getHost().getOperatingSystemVersion();
    switch (osv.getPkey()) {
      case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 :
        cryptAlgorithm = PosixFile.CryptAlgorithm.MD5;
        break;
      case OperatingSystemVersion.CENTOS_7_X86_64 :
        cryptAlgorithm = PosixFile.CryptAlgorithm.SHA512;
        break;
      default :
        throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
    }
    ShadowFile.setPassword(username, plain_password, cryptAlgorithm, updateChangedDate);
  }

  private static LinuxAccountManager linuxAccountManager;
  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  public static void start() throws IOException, SQLException {
    Server thisServer = AOServDaemon.getThisServer();
    OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
    int osvId = osv.getPkey();

    synchronized (System.out) {
      if (
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
        if (
          osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
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
    try (
      TempFileContext tempFileContext = new TempFileContext();
      TempFile tempFile = tempFileContext.createTempFile("tar_home_directory_", ".tar")
    ) {
      AOServDaemon.exec(
        "/bin/tar",
        "-c",
        "-C",
        home.toString(),
        "-f",
        tempFile.getFile().getPath(),
        "."
      );
      try (InputStream in = new FileInputStream(tempFile.getFile())) {
        byte[] buff = BufferManager.getBytes();
        try {
          int ret;
          while ((ret = in.read(buff, 0, BufferManager.BUFFER_SIZE)) != -1) {
            out.writeByte(AOServDaemonProtocol.NEXT);
            out.writeShort(ret);
            out.write(buff, 0, ret);
          }
        } finally {
          BufferManager.release(buff, false);
        }
      }
    }
  }

  public static void untarHomeDirectory(StreamableInput in, User.Name username) throws IOException, SQLException {
    Server thisServer = AOServDaemon.getThisServer();
    int uid_min = thisServer.getUidMin().getId();
    int gid_min = thisServer.getGidMin().getId();
    synchronized (rebuildLock) {
      UserServer lsa = thisServer.getLinuxServerAccount(username);
      PosixPath home = lsa.getHome();
      try (
        TempFileContext tempFileContext = new TempFileContext();
        TempFile tempFile = tempFileContext.createTempFile("untar_home_directory_", ".tar")
      ) {
        int code;
        try (OutputStream out = new FileOutputStream(tempFile.getFile())) {
          byte[] buff = BufferManager.getBytes();
          try {
            while ((code = in.readByte()) == AOServDaemonProtocol.NEXT) {
              int len = in.readShort();
              in.readFully(buff, 0, len);
              out.write(buff, 0, len);
            }
          } finally {
            BufferManager.release(buff, false);
          }
        }
        if (code != AOServDaemonProtocol.DONE) {
          if (code == AOServDaemonProtocol.IO_EXCEPTION) {
            throw new IOException(in.readUTF());
          } else if (code == AOServDaemonProtocol.SQL_EXCEPTION) {
            throw new SQLException(in.readUTF());
          } else {
            throw new IOException("Unknown result: " + code);
          }
        }
        AOServDaemon.exec(
          "/bin/tar",
          "-x",
          "-C",
          home.toString(),
          "-f",
          tempFile.getFile().getPath()
        );
      }
    }
  }

  public static void waitForRebuild() {
    if (linuxAccountManager != null) {
      linuxAccountManager.waitForBuild();
    }
  }

  @Override
  public String getProcessTimerDescription() {
    return "Rebuild Linux Accounts";
  }

  @Override
  public long getProcessTimerMaximumTime() {
    return 15L * 60 * 1000;
  }

  /**
   * Allows manual rebuild without the necessity of running the entire daemon (use carefully, only when main daemon not running).
   */
  @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch", "UseOfSystemOutOrSystemErr"})
  public static void main(String[] args) {
    try {
      rebuildLinuxAccountSettings();
    } catch (ThreadDeath td) {
      throw td;
    } catch (Throwable t) {
      ErrorPrinter.printStackTraces(t, System.err);
      System.exit(SysExits.getSysExit(t));
    }
  }

  private LinuxAccountManager() {
    // Do nothing
  }
}
