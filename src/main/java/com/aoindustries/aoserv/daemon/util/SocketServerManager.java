/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2002-2013, 2017, 2018, 2020, 2021, 2022  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.util;

import com.aoapps.net.InetAddress;
import com.aoindustries.aoserv.client.net.Bind;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages a group of <code>SocketServer</code>.  Servers may be
 * added and removed on the fly.
 *
 * @author  AO Industries, Inc.
 */
public abstract class SocketServerManager {

  private static final Logger logger = Logger.getLogger(SocketServerManager.class.getName());

  /**
   * All of the servers that are currently running.
   */
  private final List<SocketServerThread> socketServers = new ArrayList<>();

  protected SocketServerManager() {
    // Do nothing
  }

  private boolean started;

  @SuppressWarnings({"UseOfSystemOutOrSystemErr", "UseSpecificCatch", "SleepWhileInLoop", "BroadCatchBlock", "TooBroadCatch", "SleepWhileHoldingLock"})
  public final void start() throws IOException, SQLException {
    if (!started) {
      synchronized (System.out) {
        if (!started) {
          System.out.print("Starting ");
          System.out.print(getManagerName());
          System.out.println(":");
          startImpl();
          while (!started && !Thread.currentThread().isInterrupted()) {
            try {
              verifySocketServers();
              started = true;
            } catch (ThreadDeath td) {
              throw td;
            } catch (Throwable t) {
              logger.log(Level.SEVERE, null, t);
              try {
                Thread.sleep(15000);
              } catch (InterruptedException err) {
                logger.log(Level.WARNING, null, err);
                // Restore the interrupted status
                Thread.currentThread().interrupt();
              }
            }
          }
        }
      }
    }
  }

  @SuppressWarnings("NoopMethodInAbstractClass")
  protected void startImpl() throws IOException, SQLException {
    // No default action
  }

  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  protected void verifySocketServers() throws IOException, SQLException {
    synchronized (this) {
      Bind[] nbs = getNetBinds();

      // Create the existing list
      List<SocketServerThread> existing = new ArrayList<>(socketServers.size());
      for (SocketServerThread socketServer : socketServers) {
        existing.add(socketServer);
      }

      for (Bind nb : nbs) {
        InetAddress nbIp = nb.getIpAddress().getInetAddress();
        if (
            !nbIp.isLoopback()
                && !nbIp.isUnspecified()
        ) {
          int nbPort = nb.getPort().getPort();

          // Find in the existing list
          boolean found = false;
          for (int d = 0; d < existing.size(); d++) {
            SocketServerThread socketServer = existing.get(d);
            if (socketServer.runMore && socketServer.ipAddress.equals(nbIp) && socketServer.port == nbPort) {
              existing.remove(d);
              found = true;
              break;
            }
          }
          if (!found) {
            // Add a new one
            synchronized (System.out) {
              System.out.print("Starting ");
              System.out.print(getServiceName());
              System.out.print(" on ");
              System.out.print(nbIp.toBracketedString());
              System.out.print(':');
              System.out.print(nbPort);
              System.out.print(": ");
              SocketServerThread newServer = createSocketServerThread(nbIp, nbPort);
              socketServers.add(newServer);
              newServer.start();
              System.out.println("Done");
            }
          }
        }
      }
      // Shut down the extra ones
      for (SocketServerThread socketServer : existing) {
        synchronized (System.out) {
          System.out.print("Stopping ");
          System.out.print(getServiceName());
          System.out.print(" on ");
          System.out.print(socketServer.ipAddress.toBracketedString());
          System.out.print(':');
          System.out.print(socketServer.port);
          System.out.print(": ");
          socketServer.close();
          for (int d = 0; d < socketServers.size(); d++) {
            if (socketServers.get(d) == socketServer) {
              socketServers.remove(d);
              break;
            }
          }
          System.out.println("Done");
        }
      }
    }
  }

  public abstract Bind[] getNetBinds() throws IOException, SQLException;

  public abstract String getManagerName();

  public abstract String getServiceName();

  public abstract SocketServerThread createSocketServerThread(InetAddress ipAddress, int port);
}
