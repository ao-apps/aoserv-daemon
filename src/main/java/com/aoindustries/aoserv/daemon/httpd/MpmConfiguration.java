/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2025  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.httpd;

import com.aoindustries.aoserv.client.web.HttpdServer;
import java.io.IOException;
import java.sql.SQLException;

/**
 * The result of a MPM configuration calculation.  This is used both to configure the server and as a basis to
 * interpret process state into current concurrency for monitoring.
 *
 * <p>TODO: Watch number of available processors and rebuild configs when changed.</p>
 *
 * <p>TODO: AH00513: WARNING: MaxRequestWorkers of 200 is not an integer multiple of ThreadsPerChild of 13, decreasing to nearest multiple 195, for a maximum of 15 servers.</p>
 *
 * @author  AO Industries, Inc.
 */
public final class MpmConfiguration {

  /**
   * MPM tuning: The number of listeners' buckets.
   * Note, Apache <a href="https://httpd.apache.org/docs/2.4/mod/mpm_common.html#listencoresbucketsratio">recommends a ratio of 8</a>.
   */
  private static final int LISTEN_CORES_BUCKETS_RATIO = 8;

  /**
   * MPM tuning: The divisor from maximum concurrency to calculate maximum spare concurrency.
   */
  private static final int MAX_SPARE_CONCURRENCY_DIVISOR = 10;

  /**
   * The maximum value used for max spare concurrency in worker or event.
   * Matches the <a href="https://httpd.apache.org/docs/2.4/mod/mpm_common.html#maxsparethreads">Apache default of 250</a>.
   */
  private static final int MAX_WORKER_MAX_SPARE_THREADS = 250;

  /**
   * The maximum value used for max spare concurrency in prefork.
   * Matches the <a href="https://httpd.apache.org/docs/2.4/mod/prefork.html#maxspareservers">Apache default of 10</a>.
   */
  private static final int MAX_PREFORK_MAX_SPARE_CONCURRENCY = 10;

  /**
   * MPM tuning: The divisor from maximum spare concurrency to calculate minimum spare concurrency.
   */
  private static final int MIN_SPARE_CONCURRENCY_DIVISOR = 2;

  /**
   * MPM tuning: The minimum value use for <a href="https://httpd.apache.org/docs/2.4/mod/prefork.html#minspareservers">MinSpareServers Directive</a>
   * (Apache default of 5) or <a href="https://httpd.apache.org/docs/2.4/mod/mpm_common.html#minsparethreads">MinSpareThreads Directive</a>
   * (Apache default of 75).
   */
  private static final int MIN_SPARE_CONCURRENCY = 1;

  /**
   * MPM tuning: The target number of server processes per CPU core.
   * Note, Apache <a href="https://httpd.apache.org/docs/2.4/mod/mpm_common.html#serverlimit">defaults to 16 server processes</a>.
   */
  private static final int MPM_SERVERS_PER_CPU = 1;

  /**
   * MPM tuning: The minimum number of server processes.
   * Note, Apache <a href="https://httpd.apache.org/docs/2.4/mod/mpm_common.html#serverlimit">defaults to 16 server processes</a>.
   *
   * <p>Monitoring is currently only as granular as the number of servers.</p>
   *
   * <p>When fewer than this number of servers would be created (once brought down by MPM_MIN_THREADS_PER_CHILD),
   * reverts to prefork.</p>
   */
  private static final int MPM_MIN_SERVERS = 16;

  /**
   * MPM tuning: The minimum number of threads per server process.
   * Note, Apache <a href="https://httpd.apache.org/docs/2.4/mod/mpm_common.html#threadsperchild">defaults to 25 threads per server process</a>.
   *
   * <p>When fewer than this number of threads would be created, reverts to prefork.</p>
   */
  private static final int MPM_MIN_THREADS_PER_CHILD = 4;

  /**
   * MPM tuning: The maximum number of threads per server process.
   * Note, Apache <a href="https://httpd.apache.org/docs/2.4/mod/mpm_common.html#threadsperchild">defaults to 25 threads per server process</a>.
   */
  private static final int MPM_MAX_THREADS_PER_CHILD = 1000;

  /**
   * Divides rounding up to be equal to or greater than original value.
   */
  private static int ceilDiv(int dividend, int divisor) {
    if (dividend < 0) {
      throw new IllegalArgumentException("divident < 0: " + dividend);
    }
    if (divisor < 1) {
      throw new IllegalArgumentException("divisor < 1: " + divisor);
    }
    int result = dividend / divisor;
    if ((result * divisor) < dividend) {
      result++;
    }
    assert (result * divisor) >= dividend;
    return result;
  }

  enum MpmType {
    PREFORK,
    WORKER,
    EVENT
  }

  /**
   * See <a href="https://httpd.apache.org/docs/2.4/mod/mpm_common.html#listencoresbucketsratio">ListenCoresBucketsRatio Directive</a>.
   */
  final int listenCoresBucketsRatio;

  /**
   * See <a href="https://httpd.apache.org/docs/2.4/mod/prefork.html#maxspareservers">MaxSpareServers Directive</a>.
   */
  final int preforkMaxSpareServers;

  /**
   * See <a href="https://httpd.apache.org/docs/2.4/mod/prefork.html#minspareservers">MinSpareServers Directive</a>.
   */
  final int preforkMinSpareServers;

  /**
   * MaxRequestWorkers used when prefork.
   * See <a href="https://httpd.apache.org/docs/2.4/mod/mpm_common.html#maxrequestworkers">MaxRequestWorkers Directive</a>.
   */
  final int preforkMaxRequestWorkers;

  /**
   * ServerLimit used when prefork.
   * See <a href="https://httpd.apache.org/docs/2.4/mod/mpm_common.html#serverlimit">ServerLimit Directive</a>.
   */
  final int preforkServerLimit;

  /**
   * MaxRequestWorkers used when worker or event.
   * See <a href="https://httpd.apache.org/docs/2.4/mod/mpm_common.html#maxrequestworkers">MaxRequestWorkers Directive</a>.
   */
  final int workerMaxRequestWorkers;

  /**
   * MaxSpareThreads used when worker or event.
   * See <a href="https://httpd.apache.org/docs/2.4/mod/mpm_common.html#maxsparethreads">MaxSpareThreads Directive</a>.
   */
  final int workerMaxSpareThreads;

  /**
   * MinSpareThreads used when worker or event.
   * See <a href="https://httpd.apache.org/docs/2.4/mod/mpm_common.html#minsparethreads">MinSpareThreads Directive</a>.
   */
  final int workerMinSpareThreads;

  /**
   * ServerLimit used when worker or event.
   * See <a href="https://httpd.apache.org/docs/2.4/mod/mpm_common.html#serverlimit">ServerLimit Directive</a>.
   */
  final int workerServerLimit;

  /**
   * ThreadLimit used when worker or event.
   * See <a href="https://httpd.apache.org/docs/2.4/mod/mpm_common.html#threadlimit">ThreadLimit Directive</a>.
   */
  final int workerThreadLimit;

  /**
   * ThreadLimit used when worker or event.
   * See <a href="https://httpd.apache.org/docs/2.4/mod/mpm_common.html#threadsperchild">ThreadsPerChild Directive</a>.
   */
  final int workerThreadsPerChild;

  /**
   * The selected MPM type.
   */
  final MpmType type;

  public MpmConfiguration(HttpdServer hs) throws IOException, SQLException {
    final int maxConcurrency = hs.getMaxConcurrency();
    final int availableProcessors = Runtime.getRuntime().availableProcessors();
    final HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();

    // Use the constant recommended value
    listenCoresBucketsRatio = LISTEN_CORES_BUCKETS_RATIO;

    // Scale maxSpare from maxConcurrency, but capped to a maximum
    int mpmMaxSpareThreads = Math.min(maxConcurrency / MAX_SPARE_CONCURRENCY_DIVISOR, MAX_WORKER_MAX_SPARE_THREADS);
    int preMaxSpareServers = Math.min(maxConcurrency / MAX_SPARE_CONCURRENCY_DIVISOR, MAX_PREFORK_MAX_SPARE_CONCURRENCY);
    // Scale minSpare from maxConcurrency, but not less than MIN_SPARE_CONCURRENCY
    int mpmMinSpareConcurrency = Math.max(ceilDiv(mpmMaxSpareThreads, MIN_SPARE_CONCURRENCY_DIVISOR), MIN_SPARE_CONCURRENCY);
    int preMinSpareServers = Math.max(ceilDiv(preMaxSpareServers, MIN_SPARE_CONCURRENCY_DIVISOR), MIN_SPARE_CONCURRENCY);
    // Make sure maxSpare is greater than minSpare
    if (mpmMaxSpareThreads <= mpmMinSpareConcurrency) {
      mpmMaxSpareThreads = mpmMinSpareConcurrency + 1;
    }
    if (preMaxSpareServers <= preMinSpareServers) {
      preMaxSpareServers = preMinSpareServers + 1;
    }

    preforkMaxSpareServers = preMaxSpareServers;
    preforkMinSpareServers = preMinSpareServers;
    preforkServerLimit = maxConcurrency;
    preforkMaxRequestWorkers = maxConcurrency;

    // Scale by number of CPU cores, with a minimum
    int mpmServerLimit = Math.max(availableProcessors * MPM_SERVERS_PER_CPU, MPM_MIN_SERVERS);
    // Compute the number of threads per server to achieve the max concurrency
    int mpmThreadsPerChild = ceilDiv(maxConcurrency, mpmServerLimit);
    if (mpmThreadsPerChild < MPM_MIN_THREADS_PER_CHILD) {
      // Increase threads, reduce servers
      mpmThreadsPerChild = MPM_MIN_THREADS_PER_CHILD;
      mpmServerLimit = ceilDiv(maxConcurrency, mpmThreadsPerChild);
    } else if (mpmThreadsPerChild > MPM_MAX_THREADS_PER_CHILD) {
      // Reduce threads, increase servers
      mpmThreadsPerChild = MPM_MAX_THREADS_PER_CHILD;
      mpmServerLimit = ceilDiv(maxConcurrency, mpmThreadsPerChild);
    }
    assert maxConcurrency <= (mpmServerLimit * mpmThreadsPerChild);

    workerMaxRequestWorkers = maxConcurrency;
    workerMaxSpareThreads = mpmMaxSpareThreads;
    workerMinSpareThreads = mpmMinSpareConcurrency;
    workerServerLimit = mpmServerLimit;
    workerThreadLimit = mpmThreadsPerChild;
    workerThreadsPerChild = mpmThreadsPerChild;

    if (
        osConfig.isApacheDefaultPrefork()
        || hs.getModPhpVersion() != null
          || workerServerLimit < MPM_MIN_SERVERS
          || workerThreadsPerChild < MPM_MIN_THREADS_PER_CHILD
    ) {
      type = MpmType.PREFORK;
    } else {
      type = MpmType.EVENT;
    }
  }

  public int getConcurrencyPerChildProcess() {
    switch (type) {
      case PREFORK:
        return 1;
      case WORKER:
      case EVENT:
        return workerThreadsPerChild;
      default:
        throw new AssertionError("Unexected type: " + type);
    }
  }
}
