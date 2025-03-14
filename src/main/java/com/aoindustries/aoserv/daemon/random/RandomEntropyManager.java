/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2004-2013, 2017, 2018, 2019, 2020, 2021, 2022, 2024, 2025  AO Industries, Inc.
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
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.AoservDaemonConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
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
   * The minimum interval between calls to {@code getMasterEntropyNeeded()}.
   */
  public static final long GET_MASTER_ENTROPY_NEEDED_INTERVAL = 5L * 60 * 1000;

  /**
   * The delay after an error occurs.
   */
  public static final long ERROR_DELAY = 60L * 1000;

  private static final File POOLSIZE = new File("/proc/sys/kernel/random/poolsize");

  /**
   * The minimum value of poolsize, anything lower will error.
   */
  private static final int MINIMUM_POOL_SIZE = 8;

  /**
   * The number of bits available where will provide to master server.
   *
   * <p>See <code>/proc/sys/kernel/random/poolsize</code></p>
   */
  private static final int PROVIDE_THRESHOLD_NUMERATOR = 7;
  private static final int PROVIDE_THRESHOLD_DENOMINATOR = 8;

  /**
   * The number of bits available after providing to the master server.
   *
   * <p>See <code>/proc/sys/kernel/random/poolsize</code></p>
   */
  private static final int DESIRED_BITS_NUMERATOR = 6;
  private static final int DESIRED_BITS_DENONINATOR = 8;

  /**
   * The number of bits available where will obtain from master server when haveged is not supported or is not currently installed.
   *
   * <p>See <code>/proc/sys/kernel/random/poolsize</code></p>
   */
  private static final int OBTAIN_THRESHOLD_NO_HAVEGED_NUMERATOR = 1;
  private static final int OBTAIN_THRESHOLD_NO_HAVEGED_DENOMINATOR = 2;

  /**
   * The number of bits below which haveged will be automatically installed.
   * This is an attempt to keep the system non-blocking with good entropy.
   *
   * <p>See <code>/proc/sys/kernel/random/poolsize</code></p>
   */
  private static final int HAVEGED_THRESHOLD_NUMERATOR = 1;
  private static final int HAVEGED_THRESHOLD_DENOMINATOR = 4;

  /**
   * The number of bits available where will obtain from master server when haveged is supported and installed.
   * We try to let haveged take care of things before pulling from the master.
   *
   * <p>See <code>/proc/sys/kernel/random/poolsize</code></p>
   */
  private static final int OBTAIN_THRESHOLD_WITH_HAVEGED_NUMERATOR = 1;
  private static final int OBTAIN_THRESHOLD_WITH_HAVEGED_DENOMINATOR = 8;

  private static Thread thread;

  private RandomEntropyManager() {
    // Do nothing
  }

  @Override
  @SuppressWarnings({"SleepWhileInLoop", "BroadCatchBlock", "TooBroadCatch", "UseSpecificCatch"})
  public void run() {
    boolean shareEntropy = true;
    while (shareEntropy && !Thread.currentThread().isInterrupted()) {
      try {
        AoservConnector conn = AoservDaemon.getConnector();
        Server thisServer = AoservDaemon.getThisServer();
        OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
        int osvId = osv.getPkey();

        final boolean havegedSupported;
        final boolean havegedInstalled;
        final boolean newShareEntropy;
        if (
            // Supported operating systems that will automatically install haveged and share entropy
            osvId == OperatingSystemVersion.CENTOS_7_X86_64
        ) {
          havegedSupported = true;
          havegedInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.HAVEGED) != null;
          newShareEntropy = true;
        } else if (
            // Supported operating systems that will automatically install haveged but not share entropy
            osvId == OperatingSystemVersion.ROCKY_9_X86_64
        ) {
          havegedSupported = true;
          havegedInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.HAVEGED) != null;
          // Rocky 9 changed poolsize from 4096 to 256.  Entropy sharing may not longer be required, and comes at a
          // security risk.  Do not share pool on Rocky 9 until we have a compelling need to do so.
          // See https://lore.kernel.org/lkml/20220527084855.501642285@linuxfoundation.org/
          // See https://unix.stackexchange.com/questions/704737/kernel-5-10-119-caused-the-values-of-proc-sys-kernel-random-entropy-avail-and-p
          newShareEntropy = false;
        } else if (
            // Supported operating systems that will not automatically install haveged and share entropy
            osvId == OperatingSystemVersion.CENTOS_5_DOM0_I686
                || osvId == OperatingSystemVersion.CENTOS_5_DOM0_X86_64
                || osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                || osvId == OperatingSystemVersion.CENTOS_7_DOM0_X86_64
        ) {
          havegedSupported = false;
          havegedInstalled = false;
          newShareEntropy = true;
        } else {
          throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
        }
        shareEntropy = newShareEntropy;
        if (!shareEntropy) {
          // Simply install haveged then exit thread
          if (havegedSupported && !havegedInstalled) {
            PackageManager.installPackage(
                PackageManager.PackageName.HAVEGED,
                () -> {
                  AoservDaemon.exec("/usr/bin/systemctl", "enable", "haveged.service");
                  AoservDaemon.exec("/usr/bin/systemctl", "start",  "haveged.service");
                }
            );
          }
        } else {
          // Read current kernel pool size
          int poolsize;
          try (BufferedReader in = new BufferedReader(new FileReader(POOLSIZE, StandardCharsets.UTF_8))) {
            poolsize = Integer.parseInt(in.readLine());
          }
          if (poolsize < MINIMUM_POOL_SIZE) {
            throw new IllegalStateException(POOLSIZE + " is much lower than expected.  Expected >= " + MINIMUM_POOL_SIZE + ", got " + poolsize);
          }
          final int provideThreshold = Math.multiplyExact(poolsize, PROVIDE_THRESHOLD_NUMERATOR) / PROVIDE_THRESHOLD_DENOMINATOR;
          final int desiredBits = Math.multiplyExact(poolsize, DESIRED_BITS_NUMERATOR) / DESIRED_BITS_DENONINATOR;
          final int obtainThresholdNoHaveged = Math.multiplyExact(poolsize, OBTAIN_THRESHOLD_NO_HAVEGED_NUMERATOR) / OBTAIN_THRESHOLD_NO_HAVEGED_DENOMINATOR;
          final int havegedThreshold = Math.multiplyExact(poolsize, HAVEGED_THRESHOLD_NUMERATOR) / HAVEGED_THRESHOLD_DENOMINATOR;
          final int obtainThresholdWithHaveged = Math.multiplyExact(poolsize, OBTAIN_THRESHOLD_WITH_HAVEGED_NUMERATOR) / OBTAIN_THRESHOLD_WITH_HAVEGED_DENOMINATOR;
          assert provideThreshold > desiredBits;
          assert desiredBits > obtainThresholdNoHaveged;
          assert obtainThresholdNoHaveged > havegedThreshold;
          assert havegedThreshold > obtainThresholdWithHaveged;
          int obtainThreshold;
          if (havegedInstalled) {
            obtainThreshold = obtainThresholdWithHaveged;
          } else {
            obtainThreshold = obtainThresholdNoHaveged;
          }

          long masterNeeded = Long.MIN_VALUE;
          long masterLastCheckedTime = Long.MIN_VALUE;
          boolean lastObtain = true;
          while (!Thread.currentThread().isInterrupted()) {
            long sleepyTime;
            int entropyAvail = DevRandom.getEntropyAvail();
            if (entropyAvail < obtainThreshold) {
              lastObtain = true;
              int bytesNeeded = (desiredBits - entropyAvail) / Byte.SIZE;
              if (bytesNeeded > BufferManager.BUFFER_SIZE) {
                bytesNeeded = BufferManager.BUFFER_SIZE;
              }
              boolean obtainedComplete;
              byte[] buff = BufferManager.getBytes();
              try {
                int obtained = conn.getMasterEntropy(buff, bytesNeeded);
                obtainedComplete = obtained == bytesNeeded;
                if (obtained > 0) {
                  if (obtained == BufferManager.BUFFER_SIZE) {
                    DevRandom.addEntropy(buff);
                  } else {
                    byte[] newBuff = new byte[obtained];
                    System.arraycopy(buff, 0, newBuff, 0, obtained);
                    DevRandom.addEntropy(newBuff);
                    Arrays.fill(newBuff, (byte) 0);
                  }
                  entropyAvail += obtained * Byte.SIZE;
                }
              } finally {
                BufferManager.release(buff, true);
              }
              if (havegedSupported && entropyAvail < havegedThreshold) {
                PackageManager.installPackage(
                    PackageManager.PackageName.HAVEGED,
                    () -> {
                      AoservDaemon.exec("/usr/bin/systemctl", "enable", "haveged.service");
                      AoservDaemon.exec("/usr/bin/systemctl", "start",  "haveged.service");
                    }
                );
                obtainThreshold = obtainThresholdWithHaveged;
              }
              if (!obtainedComplete) {
                sleepyTime = MAX_OBTAIN_INCOMPLETE_DELAY;
              } else if (entropyAvail < obtainThreshold) {
                // Sleep proportional to the amount of pool needed
                sleepyTime = MIN_DELAY + entropyAvail * (MAX_OBTAIN_DELAY - MIN_DELAY) / desiredBits;
              } else {
                sleepyTime = MAX_OBTAIN_DELAY;
              }
            } else {
              // Sleep longer once desired bits built-up
              if (entropyAvail >= desiredBits) {
                lastObtain = false;
              }
              if (entropyAvail >= provideThreshold) {
                int provideBytes = (entropyAvail - desiredBits) / Byte.SIZE;
                long currentTime = System.currentTimeMillis();
                if (
                    masterNeeded == Long.MIN_VALUE
                        || Math.abs(currentTime - masterLastCheckedTime) >= GET_MASTER_ENTROPY_NEEDED_INTERVAL
                ) {
                  masterNeeded = conn.getMasterEntropyNeeded();
                  masterLastCheckedTime = currentTime;
                }
                if (provideBytes > masterNeeded) {
                  provideBytes = (int) masterNeeded;
                }
                if (provideBytes > 0) {
                  if (provideBytes > BufferManager.BUFFER_SIZE) {
                    provideBytes = BufferManager.BUFFER_SIZE;
                  }
                  if (logger.isLoggable(Level.FINE)) {
                    logger.fine("Providing " + provideBytes + " bytes (" + (provideBytes * Byte.SIZE) + " bits) of entropy to master server");
                  }
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
            } catch (InterruptedException err) {
              logger.log(Level.WARNING, null, err);
              // Restore the interrupted status
              Thread.currentThread().interrupt();
            }
          }
        }
      } catch (ThreadDeath td) {
        throw td;
      } catch (Throwable t) {
        logger.log(Level.SEVERE, null, t);
        try {
          Thread.sleep(ERROR_DELAY);
        } catch (InterruptedException err) {
          logger.log(Level.WARNING, null, err);
          // Restore the interrupted status
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  public static void start() throws IOException, SQLException {
    Server thisServer = AoservDaemon.getThisServer();
    OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
    int osvId = osv.getPkey();

    synchronized (System.out) {
      if (
          // Nothing is done for these operating systems
          // All operating systems currently sharing entropy
          // Check config after OS check so config entry not needed
          AoservDaemonConfiguration.isManagerEnabled(RandomEntropyManager.class)
              && thread == null
      ) {
        System.out.print("Starting RandomEntropyManager: ");
        // Must be a supported operating system
        if (
            osvId == OperatingSystemVersion.CENTOS_5_DOM0_I686
                || osvId == OperatingSystemVersion.CENTOS_5_DOM0_X86_64
                || osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                || osvId == OperatingSystemVersion.CENTOS_7_DOM0_X86_64
                || osvId == OperatingSystemVersion.CENTOS_7_X86_64
                || osvId == OperatingSystemVersion.ROCKY_9_X86_64
        ) {
          // Avoid random manager when in failover mode
          if (AoservDaemon.getThisServer().getFailoverServer() == null) {
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
