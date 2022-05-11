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

package com.aoindustries.aoserv.daemon.distro;

import static com.aoindustries.aoserv.client.distribution.Architecture.I686_AND_X86_64;
import static com.aoindustries.aoserv.client.distribution.Architecture.X86_64;
import static com.aoindustries.aoserv.client.distribution.OperatingSystem.CENTOS;
import static com.aoindustries.aoserv.client.distribution.OperatingSystemVersion.CENTOS_5_DOM0_X86_64;
import static com.aoindustries.aoserv.client.distribution.OperatingSystemVersion.CENTOS_5_I686_AND_X86_64;
import static com.aoindustries.aoserv.client.distribution.OperatingSystemVersion.CENTOS_7_DOM0_X86_64;
import static com.aoindustries.aoserv.client.distribution.OperatingSystemVersion.CENTOS_7_X86_64;
import static com.aoindustries.aoserv.client.distribution.OperatingSystemVersion.VERSION_5;
import static com.aoindustries.aoserv.client.distribution.OperatingSystemVersion.VERSION_5_DOM0;
import static com.aoindustries.aoserv.client.distribution.OperatingSystemVersion.VERSION_7;
import static com.aoindustries.aoserv.client.distribution.OperatingSystemVersion.VERSION_7_DOM0;

import com.aoapps.encoding.TextInPsqlEncoder;
import com.aoapps.hodgepodge.io.ByteCountInputStream;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.io.posix.Stat;
import com.aoapps.lang.EmptyArrays;
import com.aoapps.lang.Strings;
import com.aoapps.lang.SysExits;
import com.aoapps.lang.Throwables;
import com.aoapps.lang.exception.WrappedException;
import com.aoapps.lang.io.IoUtils;
import com.aoindustries.aoserv.client.distribution.management.DistroFileType;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.EmptyStackException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.Stack;
import java.util.TreeSet;

/**
 * Creates the server distribution database contents.
 *
 * @author  AO Industries, Inc.
 */
public final class DistroGenerator {

  private static final String DEFAULT_ROOT = "/distro";

  /**
   * The default number of threads to run per processor core.
   */
  private static final int DEFAULT_THREADS_PER_PROCESSOR = 2;

  /**
   * The default minimum number of threads.  This is a lower bound
   * on I/O concurrency even when a system has few processor cores.
   */
  private static final int DEFAULT_MIN_THREADS = 4;

  /**
   * Config file extensions.
   */
  private enum ConfigFile {
    CONFIGS_TXT(".configs.txt"),
    NEVERS_TXT(".nevers.txt"),
    NO_RECURSES_TXT(".no_recurses.txt"),
    OPTIONALS_TXT(".optionals.txt"),
    PRELINKS_TXT(".prelinks.txt"),
    USERS_TXT(".users.txt");

    private final String fileExtension;

    private ConfigFile(String fileExtension) {
      this.fileExtension = fileExtension;
    }

    /**
     * Avoid creating arrays each use.
     */
    private static final ConfigFile[] values = values();
  }

  public static class FoundNeversException extends Exception {

    private static final long serialVersionUID = 1L;

    private final Set<String> foundNevers;

    private FoundNeversException(Set<String> foundNevers) {
      super("One or more files exist that are listed in nevers");
      assert !foundNevers.isEmpty();
      this.foundNevers = foundNevers;
    }

    @SuppressWarnings("ReturnOfCollectionOrArrayField")
    public Set<String> getFoundNevers() {
      return Collections.unmodifiableSet(foundNevers);
    }
  }

  @SuppressWarnings({"UseOfSystemOutOrSystemErr", "UseSpecificCatch", "TooBroadCatch"})
  public static void main(String[] args) {
    try {
      DistroGenerator generator;
      if (args.length > 0) {
        generator = new DistroGenerator(args[0]);
      } else {
        generator = new DistroGenerator();
      }
      PrintWriter out = new PrintWriter(System.out);
      PrintWriter err = new PrintWriter(System.err);
      try {
        generator.run(out, err);
      } finally {
        out.flush();
        err.flush();
      }
    } catch (FoundNeversException e) {
      System.err.println("Files exist but are listed in nevers:");
      for (String filename : e.getFoundNevers()) {
        System.err.println(filename);
      }
      System.err.flush();
      System.exit(SysExits.EX_SOFTWARE);
    } catch (InterruptedException e) {
      // Restore the interrupted status
      Thread.currentThread().interrupt();
      System.exit(SysExits.getSysExit(e));
    } catch (Throwable t) {
      System.exit(SysExits.getSysExit(t));
    }
  }

  private final String root;

  private final int numThreads;

  public DistroGenerator(
      String root,
      int numThreads
  ) {
    this.root = root;
    this.numThreads = numThreads;
  }

  public DistroGenerator(String root) {
    this(
        root,
        Math.max(DEFAULT_MIN_THREADS, Runtime.getRuntime().availableProcessors() * DEFAULT_THREADS_PER_PROCESSOR)
    );
  }

  public DistroGenerator() {
    this(DEFAULT_ROOT);
  }

  private class OsFilename implements Comparable<OsFilename> {

    /**
     * The OperatingSystemVersion ID, or {@code -1} when not in a template directory.
     */
    private final int osv;

    /**
     * The path within the template directory, or {@code null} when not in a template directory.
     */
    private final String filename;

    private OsFilename(int osv, String filename) {
      this.osv = osv;
      this.filename = filename;
    }

    /**
     * Parses the path within the distro directory.
     */
    private OsFilename(String path) {
      // Skip leading '/'
      if (path.length() > 0 && path.charAt(0) == '/') {
        path = path.substring(1);
      }
      // Find first '/'
      int pos1 = path.indexOf('/');
      if (pos1 == -1) {
        osv = -1;
        filename = null;
      } else {
        // Find second '/'
        String osName = path.substring(0, pos1);
        int pos2 = path.indexOf('/', pos1 + 1);
        if (pos2 == -1) {
          osv = -1;
          filename = null;
        } else {
          // Find third '/'
          String osVersion = path.substring(pos1 + 1, pos2);
          int pos3 = path.indexOf('/', pos2 + 1);
          if (pos3 == -1) {
            String osArchitecture = path.substring(pos2 + 1);
            // Determine if is a config file
            boolean isConfigFile = false;
            for (ConfigFile configFile : ConfigFile.values) {
              if (osArchitecture.endsWith(configFile.fileExtension)) {
                isConfigFile = true;
                break;
              }
            }
            if (isConfigFile) {
              // Skip config files
              osv = -1;
              filename = null;
            } else {
              // Is root for one operation system version
              osv = getOperatingSystemVersion(osName, osVersion, osArchitecture);
              filename = "/";
            }
          } else {
            String osArchitecture = path.substring(pos2 + 1, pos3);
            osv = getOperatingSystemVersion(osName, osVersion, osArchitecture);
            filename = path.substring(pos3);
          }
        }
      }
    }

    @Override
    public String toString() {
      return getFullPath();
    }

    @Override
    public int hashCode() {
      return osv * 31 + Objects.hashCode(filename);
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof OsFilename)) {
        return false;
      }
      OsFilename other = (OsFilename) obj;
      return
          osv == other.osv
              && Objects.equals(filename, other.filename);
    }

    @Override
    public int compareTo(OsFilename o) {
      int diff = Integer.compare(osv, o.osv);
      if (diff != 0) {
        return diff;
      }
      // nulls before non-nulls
      if (filename == null) {
        if (o.filename == null) {
          return 0;
        }
        return -1;
      } else {
        if (o.filename == null) {
          return 1;
        }
        return filename.compareTo(o.filename);
      }
    }

    public String getOsName() {
      switch (osv) {
        case CENTOS_5_I686_AND_X86_64:
        case CENTOS_5_DOM0_X86_64:
        case CENTOS_7_X86_64:
        case CENTOS_7_DOM0_X86_64:
          return CENTOS;
        default:
          throw new RuntimeException("Unsupported operating_system_version: " + osv);
      }
    }

    public String getOsVersion() {
      switch (osv) {
        case CENTOS_5_I686_AND_X86_64:
          return VERSION_5;
        case CENTOS_5_DOM0_X86_64:
          return VERSION_5_DOM0;
        case CENTOS_7_X86_64:
          return VERSION_7;
        case CENTOS_7_DOM0_X86_64:
          return VERSION_7_DOM0;
        default:
          throw new RuntimeException("Unsupported operating_system_version: " + osv);
      }
    }

    public String getOsArchitecture() {
      switch (osv) {
        case CENTOS_5_I686_AND_X86_64:
          return I686_AND_X86_64;
        case CENTOS_5_DOM0_X86_64:
        case CENTOS_7_X86_64:
        case CENTOS_7_DOM0_X86_64:
          return X86_64;
        default:
          throw new RuntimeException("Unexpected value for osv: " + osv);
      }
    }

    public String getFullPath() {
      return root + '/' + getOsName() + '/' + getOsVersion() + '/' + getOsArchitecture() + filename;
    }
  }

  /**
   * Gets the path containing the distribution template, without any trailing
   * slash.
   */
  private String getOperatingSystemPath(int osv) {
    switch (osv) {
      case CENTOS_5_I686_AND_X86_64:
        return root + '/' + CENTOS + '/' + VERSION_5      + '/' + I686_AND_X86_64;
      case CENTOS_5_DOM0_X86_64:
        return root + '/' + CENTOS + '/' + VERSION_5_DOM0 + '/' + X86_64;
      case CENTOS_7_X86_64:
        return root + '/' + CENTOS + '/' + VERSION_7      + '/' + X86_64;
      case CENTOS_7_DOM0_X86_64:
        return root + '/' + CENTOS + '/' + VERSION_7_DOM0 + '/' + X86_64;
      default:
        throw new RuntimeException("Unexpected value for osv: " + osv);
    }
  }

  private final Map<Integer, Map<Integer, String>> usernames = new HashMap<>();

  private String getUsername(OsFilename osFilename, int fileUid) throws IOException {
    Integer osv = osFilename.osv;
    synchronized (usernames) {
      Map<Integer, String> realNames = usernames.get(osv);
      if (realNames == null) {
        realNames = new HashMap<>();
        try (BufferedReader in = new BufferedReader(
            new InputStreamReader(
                new BufferedInputStream(
                    new FileInputStream(
                        new PosixFile(
                            root + '/' + osFilename.getOsName() + '/' + osFilename.getOsVersion() + '/' + osFilename.getOsArchitecture() + "/etc/passwd"
                        ).getFile()
                    )
                )
            )
        )) {
          String line;
          while ((line = in.readLine()) != null) {
            List<String> fields = Strings.split(line, ':');
            String username = fields.get(0);
            Integer userId = Integer.parseInt(fields.get(2));
            if (!realNames.containsKey(userId)) {
              realNames.put(userId, username);
            }
          }
        }
        usernames.put(osv, realNames);
      }
      String username = realNames.get(fileUid);
      if (username == null) {
        throw new IOException("Unable to find username: " + fileUid + " for file " + osFilename);
      }
      return username;
    }
  }

  private final Map<Integer, Map<Integer, String>> groupnames = new HashMap<>();

  private String getGroupname(OsFilename osFilename, int fileGid) throws IOException {
    Integer i = osFilename.osv;
    synchronized (groupnames) {
      Map<Integer, String> realGroups = groupnames.get(i);
      if (realGroups == null) {
        realGroups = new HashMap<>();
        try (BufferedReader in = new BufferedReader(
            new InputStreamReader(
                new BufferedInputStream(
                    new FileInputStream(
                        new PosixFile(
                            root + '/' + osFilename.getOsName() + '/' + osFilename.getOsVersion() + '/' + osFilename.getOsArchitecture() + "/etc/group"
                        ).getFile()
                    )
                )
            )
        )) {
          String line;
          while ((line = in.readLine()) != null) {
            List<String> fields = Strings.split(line, ':');
            String groupname = fields.get(0);
            Integer groupId = Integer.parseInt(fields.get(2));
            if (!realGroups.containsKey(groupId)) {
              realGroups.put(groupId, groupname);
            }
          }
        }
        groupnames.put(i, realGroups);
      }
      String groupname = realGroups.get(fileGid);
      if (groupname == null) {
        throw new IOException("Unable to find group name " + fileGid + " for file " + osFilename);
      }
      return groupname;
    }
  }

  private class RunState {

    private final PrintWriter out;
    private final PrintWriter err;

    private final DistroGeneratorThread[] generators;

    /**
     * Track which filenames exist and have been seen.
     */
    private final Map<OsFilename, Boolean>
        configs = new HashMap<>(),
        nevers = new HashMap<>(),
        noRecurses = new HashMap<>(),
        optionals = new HashMap<>(),
        prelinks = new HashMap<>(),
        users = new HashMap<>();

    private final Object nextFilenameLock = new Object();

    private Stack<String> currentDirectories;
    private Stack<String[]> currentLists;
    private Stack<Integer> currentIndexes;

    private boolean done;

    /**
     * <p>
     * Reads the config file from the distro directory.
     * The filename is based on the distro directory, but with the type added.
     * For example, the file for "prelinks" on CentOS 7, x86_64 is
     * <code>/distro/centos/7/x86_64.prelinks</code>.
     * </p>
     * <p>
     * The file contains one path per line.
     * Any line beginning with <code>#</code>,
     * after trimming, is considered a comment.
     * Empty lines, after trimming, are ignored.
     * Lines containing a path are not trimmed.
     * </p>
     */
    private void readFileLists(ConfigFile configFile, Map<OsFilename, Boolean> map, int ... osvs) throws IOException {
      for (int osv : osvs) {
        String path = getOperatingSystemPath(osv) + configFile.fileExtension;
        try (
            BufferedReader in = new BufferedReader(
                new InputStreamReader(
                    new FileInputStream(path)
                )
            )
            ) {
          String filename;
          while ((filename = in.readLine()) != null) {
            String trimmed = filename.trim();
            if (trimmed.length() > 0 && trimmed.charAt(0) != '#') {
              OsFilename osFilename = new OsFilename(osv, filename);
              if (map.put(osFilename, Boolean.FALSE) != null) {
                throw new AssertionError("Duplicate filename in " + path + ": " + filename);
              }
            }
          }
        }
      }
    }

    private RunState(
        PrintWriter out,
        PrintWriter err
    ) throws IOException {
      this.out = out;
      this.err = err;
      this.generators = new DistroGeneratorThread[numThreads];
      /*
       * Load the config files
       */
      readFileLists(
          ConfigFile.CONFIGS_TXT,
          configs,
          CENTOS_5_I686_AND_X86_64,
          CENTOS_5_DOM0_X86_64,
          CENTOS_7_X86_64,
          CENTOS_7_DOM0_X86_64
      );
      readFileLists(
          ConfigFile.NEVERS_TXT,
          nevers,
          CENTOS_5_I686_AND_X86_64,
          CENTOS_5_DOM0_X86_64,
          CENTOS_7_X86_64,
          CENTOS_7_DOM0_X86_64
      );
      readFileLists(
          ConfigFile.NO_RECURSES_TXT,
          noRecurses,
          CENTOS_5_I686_AND_X86_64,
          CENTOS_5_DOM0_X86_64,
          CENTOS_7_X86_64,
          CENTOS_7_DOM0_X86_64
      );
      readFileLists(
          ConfigFile.OPTIONALS_TXT,
          optionals,
          CENTOS_5_I686_AND_X86_64,
          CENTOS_5_DOM0_X86_64,
          CENTOS_7_X86_64,
          CENTOS_7_DOM0_X86_64
      );
      readFileLists(
          ConfigFile.PRELINKS_TXT,
          prelinks,
          CENTOS_5_I686_AND_X86_64
      // Others not prelinked
      );
      readFileLists(
          ConfigFile.USERS_TXT,
          users,
          CENTOS_5_I686_AND_X86_64,
          CENTOS_5_DOM0_X86_64,
          CENTOS_7_X86_64,
          CENTOS_7_DOM0_X86_64
      );
    }

    private OsFilename getNextFilename() throws IOException {
      synchronized (nextFilenameLock) {
        // Loop until end or found within a template directory
        while (true) {
          if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException();
          }
          if (done) {
            return null;
          }
          // Initialize the stacks, if needed
          if (currentDirectories == null) {
            (currentDirectories = new Stack<>()).push("");
            (currentLists = new Stack<>()).push(new String[]{""});
            (currentIndexes = new Stack<>()).push(0);
          }
          String currentDirectory;
          String[] currentList = null;
          int currentIndex = -1;
          try {
            currentDirectory = currentDirectories.peek();
            currentList = currentLists.peek();
            currentIndex = currentIndexes.peek();

            // Undo the stack as far as needed
            while (currentDirectory != null && currentIndex >= currentList.length) {
              currentDirectories.pop();
              currentDirectory = currentDirectories.peek();
              currentLists.pop();
              currentList = currentLists.peek();
              currentIndexes.pop();
              currentIndex = currentIndexes.peek();
            }
          } catch (EmptyStackException empty) {
            currentDirectory = null;
          }
          if (currentDirectory == null) {
            done = true;
            return null;
          }
          // Get the current filename
          String filename;
          if ("/".equals(currentDirectory)) {
            filename = "/" + currentList[currentIndex++];
          } else {
            filename = currentDirectory + '/' + currentList[currentIndex++];
          }

          // Set to the next file
          currentIndexes.pop();
          currentIndexes.push(currentIndex);

          // Recurse for directories
          OsFilename osFilename = new OsFilename(filename);
          try {
            PosixFile unixFile = new PosixFile(root + filename);
            long statMode = unixFile.getStat().getRawMode();
            if (
                !PosixFile.isSymLink(statMode)
                    && PosixFile.isDirectory(statMode)
                    && (
                    osFilename.filename == null
                        || (
                        !isUser(osFilename)
                            && !isNoRecurse(osFilename)
                    )
                )
            ) {
              // Push on stacks for next level
              currentDirectories.push(filename);
              String[] list = unixFile.list();
              if (list == null) {
                list = EmptyArrays.EMPTY_STRING_ARRAY;
              }
              Arrays.sort(list);
              currentLists.push(list);
              currentIndexes.push(0);
            }
          } catch (FileNotFoundException e) {
            err.println("Error trying to access file: " + root + filename);
            err.flush();
            throw e;
          }
          if (osFilename.osv != -1 && osFilename.filename != null) {
            return osFilename;
          }
        }
      }
    }

    /**
     * Checks if a path is in the provided configuration list.
     * Also flags the path as seen.
     */
    private boolean containsFile(
        Map<OsFilename, Boolean> osVersions,
        OsFilename osFilename
    ) {
      synchronized (osVersions) {
        Boolean seen = osVersions.get(osFilename);
        if (seen == null) {
          return false;
        }
        // Flag as seen
        if (!seen) {
          osVersions.put(osFilename, Boolean.TRUE);
        }
        // Return is in list
        return true;
      }
    }

    private boolean isConfig(OsFilename osFilename) {
      return containsFile(configs, osFilename);
    }

    private boolean isNever(OsFilename osFilename) {
      return containsFile(nevers, osFilename);
    }

    private boolean isNoRecurse(OsFilename osFilename) {
      return containsFile(noRecurses, osFilename);
    }

    private boolean isOptional(OsFilename osFilename) {
      return containsFile(optionals, osFilename);
    }

    private boolean isPrelink(OsFilename osFilename) {
      return containsFile(prelinks, osFilename);
    }

    private boolean isUser(OsFilename osFilename) {
      return containsFile(users, osFilename);
    }

    private final Object outputLock = new Object();

    private void print(String line) {
      synchronized (outputLock) {
        out.print(line);
        out.print('\n');
        out.flush();
      }
    }
  }

  public void run(PrintWriter out, PrintWriter err) throws IOException, FoundNeversException, InterruptedException {
    // Initialize the run state.  This is shared between threads to make
    // this generator completely thread-safe.
    RunState runState = new RunState(out, err);

    // First do a quick scan for nevers
    Set<String> foundNevers = new LinkedHashSet<>();
    for (Map.Entry<OsFilename, Boolean> entry : runState.nevers.entrySet()) {
      OsFilename osFilename = entry.getKey();
      String path = osFilename.getFullPath();
      PosixFile uf = new PosixFile(path);
      if (uf.getStat().exists()) {
        foundNevers.add(path);
      }
    }
    if (!foundNevers.isEmpty()) {
      throw new FoundNeversException(foundNevers);
    }
    synchronized (runState.outputLock) {
      runState.out.print("begin;\n"
          + "select setval('distro_files_pkey_seq', 1, false);\n"
          + "create temp table distro_files_tmp (\n"
          + "  pkey integer\n"
          + "    not null,\n"
          + "  operating_system_version integer\n"
          + "    not null,\n"
          + "  path text\n"
          + "    not null,\n"
          + "  optional bool\n"
          + "    not null,\n"
          + "  type text\n"
          + "    not null,\n"
          + "  mode int8\n"
          + "    not null,\n"
          + "  linux_account text\n"
          + "    not null,\n"
          + "  linux_group text\n"
          + "    not null,\n"
          + "  size int8,\n"
          + "  file_sha256_0 int8,\n"
          + "  file_sha256_1 int8,\n"
          + "  file_sha256_2 int8,\n"
          + "  file_sha256_3 int8,\n"
          + "  symlink_target text\n"
          + ");\n");
      runState.out.flush();
    }
    // TODO: We could/should use the Executors API here?  This is old style direct thread manipulation, but works so not changing at this time.
    // Create and start the threads
    for (int c = 0; c < numThreads; c++) {
      runState.generators[c] = new DistroGeneratorThread(runState);
      runState.generators[c].start();
    }
    // Join each thread and throw any exceptions from them
    for (DistroGeneratorThread generator : runState.generators) {
      generator.join();
      // Throw any exception from the worker thread
      synchronized (generator.exceptionLock) {
        if (generator.ioException != null) {
          throw generator.ioException;
        }
        if (generator.foundNeversException != null) {
          throw generator.foundNeversException;
        }
        Throwable t = generator.throwable;
        if (t != null) {
          throw Throwables.wrap(t, WrappedException.class, WrappedException::new);
        }
      }
    }
    // Finish the program
    synchronized (runState.outputLock) {
      runState.out.print("select\n"
          + "  dft.*\n"
          + "from\n"
          + "  distro_files_tmp dft\n"
          + "  left outer join linux_accounts la on dft.linux_account=la.username\n"
          + "  left outer join linux_groups lg on dft.linux_group=lg.name\n"
          + "where\n"
          + "  la.username is null\n"
          + "  or lg.name is null\n"
          + "order by\n"
          + "  dft.operating_system_version,\n"
          + "  dft.path\n"
          + ";\n"
          + "delete from distro_files;\n"
          + "insert into distro_files select * from distro_files_tmp;\n"
          + "drop table distro_files_tmp;\n"
          + "commit;\n"
          + "vacuum full analyze distro_files;\n");
      runState.out.flush();
    }

    // Report files that in are configs but not found in template
    reportMissingTemplateFiles(ConfigFile.CONFIGS_TXT, runState.configs, err);
    // ConfigFile.NEVERS_TXT: These are expected to not exist
    reportMissingTemplateFiles(ConfigFile.NO_RECURSES_TXT, runState.noRecurses, err);
    reportMissingTemplateFiles(ConfigFile.OPTIONALS_TXT, runState.optionals, err);
    reportMissingTemplateFiles(ConfigFile.PRELINKS_TXT, runState.prelinks, err);
    reportMissingTemplateFiles(ConfigFile.USERS_TXT, runState.users, err);
  }

  private class DistroGeneratorThread extends Thread {

    private final RunState runState;

    // synchronizing on threads may conflict with join method, using explicit lock for exceptions
    private final Object exceptionLock = new Object();
    private IOException ioException;
    private FoundNeversException foundNeversException;
    private Throwable throwable;

    private DistroGeneratorThread(RunState runState) {
      this.runState = runState;
    }

    @Override
    @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
    public void run() {
      try {
        MessageDigest digest = MessageDigestUtils.getSha256();
        while (!Thread.currentThread().isInterrupted()) {
          OsFilename osFilename = runState.getNextFilename();
          if (osFilename == null) {
            break;
          }
          assert osFilename.osv != -1 && osFilename.filename != null;
          String fullPath = osFilename.getFullPath();
          if (runState.isNever(osFilename)) {
            throw new FoundNeversException(Collections.singleton(fullPath));
          }
          // Determine the type
          String type = runState.isUser(osFilename) ? DistroFileType.USER
              : runState.isConfig(osFilename) ? DistroFileType.CONFIG
              : runState.isNoRecurse(osFilename) ? DistroFileType.NO_RECURSE
              : runState.isPrelink(osFilename) ? DistroFileType.PRELINK
              : DistroFileType.SYSTEM;

          // Decide if the size should be stored
          PosixFile file = new PosixFile(fullPath);
          Stat fileStat = file.getStat();
          long statMode = fileStat.getRawMode();
          boolean isRegularFile = PosixFile.isRegularFile(statMode);
          boolean storeSize = isRegularFile && !runState.isConfig(osFilename);

          // Only hash system regular files
          boolean doHash = isRegularFile && (type.equals(DistroFileType.SYSTEM) || type.equals(DistroFileType.PRELINK));

          try {
            StringBuilder sb = new StringBuilder();
            sb
                .append("insert into distro_files_tmp values (nextval('distro_files_pkey_seq'), ")
                .append(osFilename.osv)
                .append(", ");
            TextInPsqlEncoder.textInPsqlEncoder.writePrefixTo(sb);
            TextInPsqlEncoder.textInPsqlEncoder.append(osFilename.filename.length() == 0 ? "/" : osFilename.filename, sb);
            TextInPsqlEncoder.textInPsqlEncoder.writeSuffixTo(sb, false);
            sb
                .append(", ")
                .append(runState.isOptional(osFilename))
                .append(", '")
                .append(type)
                .append("', ")
                .append(statMode)
                .append("::int8, ");
            TextInPsqlEncoder.textInPsqlEncoder.writePrefixTo(sb);
            TextInPsqlEncoder.textInPsqlEncoder.append(getUsername(osFilename, fileStat.getUid()), sb);
            TextInPsqlEncoder.textInPsqlEncoder.writeSuffixTo(sb, false);
            sb.append(", ");
            TextInPsqlEncoder.textInPsqlEncoder.writePrefixTo(sb);
            TextInPsqlEncoder.textInPsqlEncoder.append(getGroupname(osFilename, fileStat.getGid()), sb);
            TextInPsqlEncoder.textInPsqlEncoder.writeSuffixTo(sb, false);
            sb.append(", ");
            if (doHash) {
              assert storeSize;
              if (type.equals(DistroFileType.SYSTEM)) {
                long fileLen = fileStat.getSize();
                byte[] sha256;
                try (ByteCountInputStream in = new ByteCountInputStream(new FileInputStream(fullPath))) {
                  sha256 = MessageDigestUtils.hashInput(digest, in);
                  // Make sure expected number of bytes read
                  long readLen = in.getCount();
                  if (readLen != fileLen) {
                    throw new IOException("readLen != fileLen: " + readLen + " != " + fileLen);
                  }
                }
                if (sha256.length != 32) {
                  throw new AssertionError();
                }
                sb
                    .append(fileLen).append("::int8, ")
                    .append(IoUtils.bufferToLong(sha256)).append("::int8, ")
                    .append(IoUtils.bufferToLong(sha256, 8)).append("::int8, ")
                    .append(IoUtils.bufferToLong(sha256, 16)).append("::int8, ")
                    .append(IoUtils.bufferToLong(sha256, 24)).append("::int8");
              } else if (type.equals(DistroFileType.PRELINK)) {
                String chroot = root + '/' + osFilename.getOsName() + '/' + osFilename.getOsVersion() + '/' + osFilename.getOsArchitecture();
                // Need to do SHA-256 digest in Java since prelink command doesn't support it directly
                AoservDaemon.execRun(
                    stdout -> {
                      byte[] sha256;
                      long fileLen;
                      try (ByteCountInputStream in = new ByteCountInputStream(stdout)) {
                        sha256 = MessageDigestUtils.hashInput(digest, in);
                        // Use length of unprelinked file
                        fileLen = in.getCount();
                      }
                      if (sha256.length != 32) {
                        throw new AssertionError();
                      }
                      sb
                          .append(fileLen).append("::int8, ")
                          .append(IoUtils.bufferToLong(sha256)).append("::int8, ")
                          .append(IoUtils.bufferToLong(sha256, 8)).append("::int8, ")
                          .append(IoUtils.bufferToLong(sha256, 16)).append("::int8, ")
                          .append(IoUtils.bufferToLong(sha256, 24)).append("::int8");
                    },
                    "/usr/sbin/chroot",
                    chroot,
                    "/usr/sbin/prelink",
                    "--verify",
                    osFilename.filename
                );
              } else {
                throw new RuntimeException("Unexpected value for type: " + type);
              }
            } else {
              if (storeSize) {
                sb.append(fileStat.getSize()).append("::int8");
              } else {
                sb.append("null");
              }
              sb.append(", null, null, null, null");
            }
            sb.append(", ");
            if (PosixFile.isSymLink(statMode)) {
              TextInPsqlEncoder.textInPsqlEncoder.writePrefixTo(sb);
              TextInPsqlEncoder.textInPsqlEncoder.append(file.readLink(), sb);
              TextInPsqlEncoder.textInPsqlEncoder.writeSuffixTo(sb, false);
            } else {
              sb.append("null");
            }
            sb.append(");");
            runState.print(sb.toString());
          } catch (IOException e) {
            runState.err.println("Error on file: " + fullPath);
            runState.err.flush();
            throw e;
          }
        }
      } catch (IOException e) {
        synchronized (exceptionLock) {
          this.ioException = e;
        }
      } catch (FoundNeversException e) {
        synchronized (exceptionLock) {
          this.foundNeversException = e;
        }
      } catch (ThreadDeath td) {
        throw td;
      } catch (Throwable t) {
        synchronized (exceptionLock) {
          this.throwable = t;
        }
      }
    }
  }

  public static int getOperatingSystemVersion(String name, String version, String architecture) {
    if (name.equals(CENTOS)) {
      if (version.equals(VERSION_5) && architecture.equals(I686_AND_X86_64)) {
        return CENTOS_5_I686_AND_X86_64;
      }
      if (version.equals(VERSION_5_DOM0) && architecture.equals(X86_64)) {
        return CENTOS_5_DOM0_X86_64;
      }
      if (version.equals(VERSION_7) && architecture.equals(X86_64)) {
        return CENTOS_7_X86_64;
      }
      if (version.equals(VERSION_7_DOM0) && architecture.equals(X86_64)) {
        return CENTOS_7_DOM0_X86_64;
      }
    }
    throw new RuntimeException("Unsupported operating system: name=" + name + ", version=" + version + ", architecture=" + architecture);
  }

  private void reportMissingTemplateFiles(ConfigFile configFile, Map<OsFilename, Boolean> map, PrintWriter err) {
    // Filter all not seen
    SortedSet<OsFilename> notSeen = new TreeSet<>();
    for (Map.Entry<OsFilename, Boolean> entry : map.entrySet()) {
      if (!entry.getValue()) {
        notSeen.add(entry.getKey());
      }
    }
    if (!notSeen.isEmpty()) {
      int lastOsv = Integer.MIN_VALUE;
      for (OsFilename osFilename : notSeen) {
        int osv = osFilename.osv;
        if (osv != lastOsv) {
          lastOsv = osv;
          err.println();
          err.println("*************************************************************************");
          err.print("* WARNING: These files are listed in ");
          err.print(getOperatingSystemPath(osv));
          err.println(configFile.fileExtension);
          err.println("* but not found in the distribution template.");
          err.println("*************************************************************************");
        }
        err.println(osFilename.filename);
      }
      err.flush();
    }
  }
}
