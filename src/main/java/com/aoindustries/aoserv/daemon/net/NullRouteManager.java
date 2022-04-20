/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2013, 2016, 2017, 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.net;

import com.aoapps.io.posix.PosixFile;
import com.aoapps.net.InetAddress;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.account.Administrator;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.master.User;
import com.aoindustries.aoserv.client.net.IpAddress;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.LinuxProcess;
import com.aoindustries.aoserv.daemon.server.VirtualServerManager;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.IOException;
import java.io.PrintStream;
import java.net.StandardProtocolFamily;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the null route config file.
 *
 * @author  AO Industries, Inc.
 */
public final class NullRouteManager {

  private static final Logger logger = Logger.getLogger(NullRouteManager.class.getName());

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

  private static final PosixFile BIRD_NULL_CONFIG = new PosixFile("/etc/opt/bird-1/bird-null-auto.conf");

  // TODO: Get this from linux_server_groups, name="bird" once LinuxAccountManager enabled on CentOS 7.dom0
  private static final int BIRD_GID = 95;

  private static volatile NullRouteManager instance;

  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  public static void start() throws IOException, SQLException {
    Server thisServer = AOServDaemon.getThisServer();
    OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
    int osvId = osv.getPkey();

    synchronized (System.out) {
      if (
        // Nothing is done for these operating systems
        osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
        && osvId != OperatingSystemVersion.CENTOS_7_X86_64
        // Check config after OS check so config entry not needed
        && AOServDaemonConfiguration.isManagerEnabled(NullRouteManager.class)
        && instance == null
      ) {
        System.out.print("Starting NullRouteManager: ");
        // Must be a supported operating system
        if (
          // Only runs on Xen dom0 (firewalling done outside virtual servers)
          osvId == OperatingSystemVersion.CENTOS_5_DOM0_I686
          || osvId == OperatingSystemVersion.CENTOS_5_DOM0_X86_64
          || osvId == OperatingSystemVersion.CENTOS_7_DOM0_X86_64
        ) {
          AOServConnector conn = AOServDaemon.getConnector();
          Administrator administrator = conn.getCurrentAdministrator();
          User mu = administrator.getMasterUser();
          if (mu == null) {
            throw new AssertionError("Administrator is not a User");
          }
          if (mu.isRouter()) {
            instance = new NullRouteManager();
            instance.startThread();
            System.out.println("Done");
          } else {
            System.out.println("Disabled: This is not a router");
          }
        } else {
          System.out.println("Unsupported OperatingSystemVersion: " + osv);
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
      if (DEBUG) {
        final PrintStream out = System.out;
        synchronized (out) {
          out.print(NullRouteManager.class.getName());
          out.print(IpAddress.getIPAddressForInt(ip));
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
      if (currentTime < startTime) {
        // System time reset to the past, start over
        level = -1;
        if (DEBUG) {
          final PrintStream out = System.out;
          synchronized (out) {
            out.print(NullRouteManager.class.getName());
            out.print(": ");
            out.print(IpAddress.getIPAddressForInt(ip));
            out.print(": system time reset: level=");
            out.println(level);
          }
        }
      } else if (currentTime >= endTime) {
        long decrementLevels = (currentTime - endTime) / REDUCE_LEVEL_QUIET_TIME;
        assert decrementLevels >= 0;
        if (decrementLevels != 0) {
          level -= Math.min(decrementLevels, durations.length);
          if (level < -1) {
            level = -1;
          }
          if (DEBUG) {
            final PrintStream out = System.out;
            synchronized (out) {
              out.print(NullRouteManager.class.getName());
              out.print(": ");
              out.print(IpAddress.getIPAddressForInt(ip));
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
      if (DEBUG) {
        final PrintStream out = System.out;
        synchronized (out) {
          out.print(NullRouteManager.class.getName());
          out.print(": ");
          out.print(IpAddress.getIPAddressForInt(ip));
          out.print(": incremented: level=");
          out.println(level);
        }
      }
    }
  }

  private static final Map<Integer, NullRoute> nullRoutes = new LinkedHashMap<>();

  private static final Object threadLock = new Object();
  private Thread thread;

  private NullRouteManager() {
    // Do nothing
  }

  private void startThread() {
    synchronized (threadLock) {
      if (thread == null) {
        thread = new Thread(NullRouteManager.class.getName()) {
          @Override
          public void run() {
            while (!Thread.currentThread().isInterrupted()) {
              try {
                final StringBuilder newContents = new StringBuilder();
                synchronized (nullRoutes) {
                  while (!Thread.currentThread().isInterrupted()) {
                    // Verify config file while cleaning-up entries
                    newContents.setLength(0);
                    final long currentTime = System.currentTimeMillis();
                    long nearestEnding = Long.MAX_VALUE;
                    Iterator<Map.Entry<Integer, NullRoute>> iter = nullRoutes.entrySet().iterator();
                    while (iter.hasNext()) {
                      Map.Entry<Integer, NullRoute> entry = iter.next();
                      NullRoute nullRoute = entry.getValue();
                      // If null route currently in progress, add to the output file
                      if (currentTime >= nullRoute.startTime && currentTime < nullRoute.endTime) {
                        String ipString = IpAddress.getIPAddressForInt(nullRoute.ip);
                        InetAddress inetAddress = InetAddress.valueOf(ipString);
                        assert inetAddress.getProtocolFamily().equals(StandardProtocolFamily.INET);
                        // Never null-route private IP addresses, such as those used for communication between routers for BGP sessions
                        if (!inetAddress.isUniqueLocal()) {
                          newContents
                            .append("route ")
                            .append(ipString)
                            .append("/32 drop;\n")
                          ;
                        }
                        // Find the null route that expires next
                        if (nullRoute.endTime < nearestEnding) {
                          nearestEnding = nullRoute.endTime;
                        }
                      } else {
                        nullRoute.reduceLevel(currentTime);
                        if (nullRoute.level < 0) {
                          // Quiet long enough to remove entirely
                          iter.remove();
                        }
                      }
                    }
                    byte[] newBytes = newContents.toString().getBytes(StandardCharsets.UTF_8.name()); // .name() only for JDK < 1.6 compatibility
                    // See if file has changed
                    if (
                      DaemonFileUtils.atomicWrite(
                        BIRD_NULL_CONFIG,
                        newBytes,
                        0640,
                        PosixFile.ROOT_UID,
                        BIRD_GID, // TODO: Enable LinuxAccountManager on CentOS 7.dom0 and get this from the existing "bird" linux_server_group
                        null,
                        null // SELinux disabled on dom0
                      )
                    ) {
                      Server thisServer = AOServDaemon.getThisServer();
                      OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
                      int osvId = osv.getPkey();
                      if (
                        osvId == OperatingSystemVersion.CENTOS_5_DOM0_I686
                        || osvId == OperatingSystemVersion.CENTOS_5_DOM0_X86_64
                      ) {
                        // kill -HUP bird if updated
                        int pid = VirtualServerManager.findPid("/opt/bird/sbin/bird\u0000-u\u0000bird\u0000-g\u0000bird");
                        if (pid == -1) {
                          logger.log(Level.SEVERE, "bird not running");
                        } else {
                          new LinuxProcess(pid).signal("HUP");
                        }
                      } else if (osvId == OperatingSystemVersion.CENTOS_7_DOM0_X86_64) {
                        try {
                          AOServDaemon.exec("/usr/bin/systemctl", "reload-or-try-restart", "bird-1.service");
                        } catch (IOException e) {
                          logger.log(Level.SEVERE, "Unable to reload bird configuration", e);
                        }
                      } else {
                        throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
                      }
                    }
                    // Wait until more action to take
                    if (nearestEnding == Long.MAX_VALUE) {
                      // No null routes active, wait indefinitely until notified
                      nullRoutes.wait(MAX_WAIT_TIME);
                    } else {
                      long waitTime = nearestEnding - System.currentTimeMillis();
                      if (waitTime > MAX_WAIT_TIME) {
                        waitTime = MAX_WAIT_TIME;
                      }
                      if (waitTime > 0) {
                        nullRoutes.wait(waitTime);
                      }
                    }
                  }
                }
              } catch (ThreadDeath td) {
                throw td;
              } catch (InterruptedException err) {
                logger.log(Level.SEVERE, null, err);
                // Restore the interrupted status
                Thread.currentThread().interrupt();
              } catch (Throwable t) {
                logger.log(Level.SEVERE, null, t);
              }
              try {
                sleep(1000);
              } catch (InterruptedException err) {
                logger.log(Level.WARNING, null, err);
                // Restore the interrupted status
                Thread.currentThread().interrupt();
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
    synchronized (nullRoutes) {
      // Look for an existing null route
      NullRoute nullRoute = nullRoutes.get(ipObj);
      if (nullRoute != null) {
        // If null route currently in progress, ignore request
        if (currentTime >= nullRoute.startTime && currentTime < nullRoute.endTime) {
          // Ignore request
          return;
        }
        nullRoute.reduceLevel(currentTime);
        if (nullRoute.level < 0) {
          // Quiet long enough to start over
          nullRoute = null;
        } else {
          // Increase level
          nullRoute.increaseLevel(currentTime);
        }
      }
      if (nullRoute == null) {
        nullRoutes.put(ipObj, new NullRoute(nullingIp, currentTime));
      }
      nullRoutes.notify(); // notifyAll() not needed: only a single thread waiting
    }
  }
}
