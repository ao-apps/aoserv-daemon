/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2008-2013, 2017, 2018, 2020, 2022  AO Industries, Inc.
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
 * Manages SharedTomcat version 3.2.4 configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdSharedTomcatManager_3_2_4 extends HttpdSharedTomcatManager_3_X<TomcatCommon_3_2_4> {

  HttpdSharedTomcatManager_3_2_4(SharedTomcat sharedTomcat) {
    super(sharedTomcat);
  }

  @Override
  TomcatCommon_3_2_4 getTomcatCommon() {
    return TomcatCommon_3_2_4.getInstance();
  }

  @Override
  protected String getOptDir() {
    return "apache-tomcat-3.2";
  }
}
