/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2021, 2022  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.web.tomcat.SharedTomcat;

/**
 * Manages SharedTomcat version 10.0.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdSharedTomcatManager_10_0_X extends VersionedSharedTomcatManager<TomcatCommon_10_0_X> {

  HttpdSharedTomcatManager_10_0_X(SharedTomcat sharedTomcat) {
    super(sharedTomcat);
  }

  @Override
  TomcatCommon_10_0_X getTomcatCommon() {
    return TomcatCommon_10_0_X.getInstance();
  }
}
