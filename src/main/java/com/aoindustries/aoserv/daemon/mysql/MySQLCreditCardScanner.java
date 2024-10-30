/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2007-2013, 2017, 2018, 2019, 2020, 2021, 2022, 2024  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.mysql;

import com.aoapps.cron.CronDaemon;
import com.aoapps.cron.CronJob;
import com.aoapps.cron.Schedule;
import com.aoapps.lang.util.ErrorPrinter;
import com.aoapps.net.Port;
import com.aoindustries.aoserv.client.account.Account;
import com.aoindustries.aoserv.client.mysql.Database;
import com.aoindustries.aoserv.client.mysql.Server;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.AoservDaemonConfiguration;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controls the MySQL databases.
 *
 * <p>TODO: Move to NOC.</p>
 *
 * @author  AO Industries, Inc.
 */
public final class MySQLCreditCardScanner implements CronJob {

  private static final Logger logger = Logger.getLogger(MySQLCreditCardScanner.class.getName());

  private MySQLCreditCardScanner() {
    // Do nothing
  }

  private static MySQLCreditCardScanner mySQLCreditCardScanner;

  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  public static void start() {
    if (AoservDaemonConfiguration.isManagerEnabled(MySQLCreditCardScanner.class) && mySQLCreditCardScanner == null) {
      synchronized (System.out) {
        if (mySQLCreditCardScanner == null) {
          System.out.print("Starting MySQLCreditCardScanner: ");
          mySQLCreditCardScanner = new MySQLCreditCardScanner();
          CronDaemon.addCronJob(mySQLCreditCardScanner, logger);
          System.out.println("Done");
        }
      }
    }
  }

  private static final Schedule schedule = (int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) ->
      minute == 30
          && hour == 2
          && dayOfWeek == Calendar.SUNDAY;

  @Override
  public Schedule getSchedule() {
    return schedule;
  }

  /**
   * Performs the scheduled task.
   */
  @Override
  public void run(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
    scanMysqlForCards();
  }

  @Override
  public int getThreadPriority() {
    return Thread.NORM_PRIORITY - 2;
  }

  public static void main(String[] args) {
    scanMysqlForCards();
  }

  @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
  private static void scanMysqlForCards() {
    try {
      com.aoindustries.aoserv.client.linux.Server thisServer = AoservDaemon.getThisServer();

      Map<Account, StringBuilder> reports = new HashMap<>();

      List<Server> mysqlServers = thisServer.getMysqlServers();
      for (Server mysqlServer : mysqlServers) {
        Server.Name serverName = mysqlServer.getName();
        Port port = mysqlServer.getBind().getPort();
        List<Database> mysqlDatabases = mysqlServer.getMysqlDatabases();
        for (Database database : mysqlDatabases) {
          Database.Name name = database.getName();

          // Get connection to the database
          Class.forName(AoservDaemonConfiguration.getMySqlDriver());
          try (Connection conn = DriverManager.getConnection(
              MySQLDatabaseManager.getJdbcUrl(port, name),
              AoservDaemonConfiguration.getMySqlUser(serverName),
              AoservDaemonConfiguration.getMySqlPassword(serverName)
          )) {
            try {
              Account account = database.getPackage().getAccount();
              StringBuilder report = reports.get(account);
              if (report == null) {
                reports.put(account, report = new StringBuilder());
              }
              scanForCards(thisServer, mysqlServer, database, conn, name.toString(), report);
            } catch (SQLException e) {
              conn.abort(AoservDaemon.executorService);
              throw e;
            }
          }
        }
      }
      for (Account account : reports.keySet()) {
        StringBuilder report = reports.get(account);
        if (report != null && report.length() > 0) {
          /* TODO
          AoservDaemon.getConnector().getCurrentAdministrator().addTicket(
            account,
            TicketType.TODO_SECURITY,
            report.toString(),
            Ticket.NO_DEADLINE,
            Priority.HIGH,
            null,
            null,
            null,
            null,
            null
          );*/
        }
      }
    } catch (ThreadDeath td) {
      throw td;
    } catch (Throwable t) {
      logger.log(Level.SEVERE, null, t);
    }
  }

  public static void scanForCards(
      com.aoindustries.aoserv.client.linux.Server thisServer,
      Server mysqlServer,
      Database database,
      Connection conn,
      String catalog,
      StringBuilder report
  ) throws SQLException {
    DatabaseMetaData metaData = conn.getMetaData();
    String[] tableTypes = new String[]{"TABLE"};
    try (ResultSet tables = metaData.getTables(catalog, null, null, tableTypes)) {
      while (tables.next()) {
        String table = tables.getString(3);
        if (Database.isSafeName(table)) {
          StringBuilder buffer = new StringBuilder();
          buffer.append("SELECT COUNT(*) FROM `").append(table).append("` WHERE ");
          try (ResultSet columns = metaData.getColumns(catalog, null, table, null)) {
            boolean isFirst = true;
            while (columns.next()) {
              String column = columns.getString(4);
              if (Database.isSafeName(column)) {
                if (isFirst) {
                  isFirst = false;
                } else {
                  buffer.append(" OR ");
                }

                buffer.append("(LENGTH(`").append(column).append("`)<25 && `").append(column).append("` REGEXP '^\\w*(");

                // AmEx
                buffer.append("3[47][0-9]{2}[\\w-]?[0-9]{2}[\\w-]?[0-9]{4}[\\w-]?[0-9]{5}");
                // Visa
                buffer.append("|").append("4[0-9]{3}[\\w-]?[0-9]{4}[\\w-]?[0-9]{4}[\\w-]?[0-9]{4}");
                //MasterCard
                buffer.append("|").append("5[1-5][0-9]{2}[\\w-]?[0-9]{4}[\\w-]?[0-9]{4}[\\w-]?[0-9]{4}");
                //Discover
                buffer.append("|").append("6011[\\w-]?[0-9]{4}[\\w-]?[0-9]{4}[\\w-]?[0-9]{4}");

                buffer.append(")\\w*$')");
              } else {
                report.append('\n')
                    .append("Unable to scan column, unsafe name\n")
                    .append('\n')
                    .append("Host........: ").append(thisServer.getHostname()).append('\n')
                    .append("MySQL Host..: ").append(mysqlServer.toString()).append('\n')
                    .append("Database......: ").append(database.getName()).append('\n')
                    .append("Table.........: ").append(table).append('\n')
                    .append("Column........: ").append(column).append('\n');
              }
            }
          }
          // Find total number of rows
          long rowCount;
          long ccCount;
          String currentSql = null;
          try (Statement stmt = conn.createStatement()) {
            try (ResultSet results = stmt.executeQuery(currentSql = "SELECT COUNT(*) FROM `" + table + "`")) {
              if (!results.next()) {
                throw new SQLException("No results returned!");
              }
              rowCount = results.getLong(1);
            }
            try (ResultSet cardnumbers = stmt.executeQuery(currentSql = buffer.toString())) {
              if (!cardnumbers.next()) {
                throw new SQLException("No results returned!");
              }
              ccCount = cardnumbers.getLong(1);
            }
          } catch (Error | RuntimeException | SQLException e) {
            ErrorPrinter.addSql(e, currentSql);
            throw e;
          }
          if (ccCount > 50 && (ccCount * 2) >= rowCount) {
            report.append('\n')
                .append("Credit cards found in database\n")
                .append('\n')
                .append("Host........: ").append(thisServer.getHostname()).append('\n')
                .append("MySQL Host..: ").append(mysqlServer.toString()).append('\n')
                .append("Database......: ").append(database.getName()).append('\n')
                .append("Table.........: ").append(table).append('\n')
                .append("Row Count.....: ").append(rowCount).append('\n')
                .append("Credit Cards..: ").append(ccCount).append('\n');
          }
        } else {
          report.append('\n')
              .append("Unable to scan table, unsafe name\n")
              .append('\n')
              .append("Host........: ").append(thisServer.getHostname()).append('\n')
              .append("MySQL Host..: ").append(mysqlServer.toString()).append('\n')
              .append("Database......: ").append(database.getName()).append('\n')
              .append("Table.........: ").append(table).append('\n');
        }
      }
    }
    // TODO: Scan for both PostgreSQL and MySQL here
    // TODO: Scan for both PostgreSQL and MySQL here
  }
}
