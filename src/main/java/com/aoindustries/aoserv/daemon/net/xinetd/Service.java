/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2003-2013, 2015, 2017, 2018, 2019, 2021, 2022  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.net.xinetd;

import com.aoapps.encoding.ChainWriter;
import com.aoapps.net.InetAddress;
import com.aoapps.net.Port;
import com.aoindustries.aoserv.client.linux.GroupServer;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.net.IpAddress;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Represents one service in the xinetd.d directory.
 */
public final class Service {

  private final String type;
  private final int instances;
  private final int per_source;
  private final String cps;
  private final String banner_fail;
  private final String flags;
  private final String service;
  private final com.aoapps.net.Protocol socket_type;
  private final IpAddress bind;
  private final Port port;
  private final boolean wait;
  private final UserServer user;
  private final GroupServer group;
  private final String server;
  private final String env;
  private final String server_args;
  private final String log_on_success;
  private final String log_on_failure;
  private final int nice;
  private final String rlimit_as;
  private final String redirect;

  public Service(
      String type,
      int instances,
      int per_source,
      String cps,
      String banner_fail,
      String flags,
      String service,
      com.aoapps.net.Protocol socket_type,
      IpAddress bind,
      Port port,
      boolean wait,
      UserServer user,
      GroupServer group,
      String server,
      String env,
      String server_args,
      String log_on_success,
      String log_on_failure,
      int nice,
      String rlimit_as,
      String redirect
  ) throws SQLException {
    this.type = type;
    this.instances = instances;
    this.per_source = per_source;
    this.cps = cps;
    this.banner_fail = banner_fail;
    this.flags = flags;
    this.service = service;
    this.socket_type = socket_type;
    this.bind = bind;
    this.port = port;
    this.wait = wait;
    this.user = user;
    this.group = group;
    this.server = server;
    this.env = env;
    this.server_args = server_args;
    this.log_on_success = log_on_success;
    this.log_on_failure = log_on_failure;
    this.nice = nice;
    this.rlimit_as = rlimit_as;
    this.redirect = redirect;

    if (redirect != null && (server != null || env != null || server_args != null)) {
      throw new SQLException("Unable to provide server, env, or server_args when a redirect is requested");
    }
  }

  public String getService() {
    return service;
  }

  public String getRedirect() {
    return redirect;
  }

  public void printXinetdConfig(ChainWriter out) throws IOException, SQLException {
    out.print("service ").print(service).print("\n"
        + "{\n");
    if (type != null) {
      out.print("\ttype = ").print(type).print('\n');
    }

    out.print("\tinstances = ");
    if (instances == -1) {
      out.print("UNLIMITED");
    } else {
      out.print(instances);
    }
    out.print('\n');

    out.print("\tper_source = ");
    if (per_source == -1) {
      out.print("UNLIMITED");
    } else {
      out.print(per_source);
    }
    out.print('\n');

    if (cps != null) {
      out.print("\tcps = ").print(cps).print('\n');
    }

    if (banner_fail != null) {
      out.print("\tbanner_fail = ").print(banner_fail).print('\n');
    }

    if (flags != null) {
      out.print("\tflags = ").print(flags).print('\n');
    }

    out.print("\tsocket_type = ");
    if (socket_type == com.aoapps.net.Protocol.TCP) {
      out.print("stream");
    } else if (socket_type == com.aoapps.net.Protocol.UDP) {
      out.print("dgram");
    } else {
      throw new SQLException("Unknown value for socket_type: " + socket_type);
    }
    out.print('\n');

    if (bind != null) {
      InetAddress ip = bind.getInetAddress();
      if (!ip.isUnspecified()) {
        out.print("\tbind = ").print(ip.toString()).print('\n');
      }
    }
    if (port != null) {
      if (port.getProtocol() != socket_type) {
        throw new SQLException("port and socket_type mismatch: " + port + " and " + socket_type);
      }
      out.print("\tport = ").print(port.getPort()).print('\n');
    }

    out.print("\twait = ").print(wait ? "yes" : "no").print('\n');

    out.print("\tuser = ").print(user.getLinuxAccount().getUsername().getUsername()).print('\n');

    if (group != null) {
      out.print("\tgroup = ").print(group.getLinuxGroup().getName()).print('\n');
    }

    if (server != null) {
      out.print("\tserver = ").print(server).print('\n');
    }

    if (env != null) {
      out.print("\tenv = ").print(env).print('\n');
    }

    if (server_args != null) {
      out.print("\tserver_args = ").print(server_args).print('\n');
    }

    if (redirect != null) {
      out.print("\tredirect = ").print(redirect).print('\n');
    }

    if (log_on_success != null) {
      out.print("\tlog_on_success = ").print(log_on_success).print('\n');
    }

    if (log_on_failure != null) {
      out.print("\tlog_on_failure = ").print(log_on_failure).print('\n');
    }

    if (nice != -1) {
      out.print("\tnice = ").print(nice).print('\n');
    }

    if (rlimit_as != null) {
      out.print("\trlimit_as = ").print(rlimit_as).print('\n');
    }

    out.print("\tdisable = no\n"
        + "}\n");
  }

  public boolean bindMatches(Service other) {
    return
        bind.equals(other.bind)
            && socket_type.equals(other.socket_type)
            && service.equals(other.service)
            && Objects.equals(port, other.port);
  }
}
