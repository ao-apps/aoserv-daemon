/*
 * Copyright 2007-2013, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.mysql;

import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.Business;
import com.aoindustries.aoserv.client.MySQLDatabase;
import com.aoindustries.aoserv.client.MySQLServer;
import com.aoindustries.aoserv.client.validator.MySQLDatabaseName;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.cron.CronDaemon;
import com.aoindustries.cron.CronJob;
import com.aoindustries.cron.CronJobScheduleMode;
import com.aoindustries.cron.Schedule;
import java.io.IOException;
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

/**
 * Controls the MySQL databases.
 * 
 * TODO: Move to NOC.
 *
 * @author  AO Industries, Inc.
 */
final public class MySQLCreditCardScanner implements CronJob {

	private MySQLCreditCardScanner() {
	}

	private static MySQLCreditCardScanner mySQLCreditCardScanner;

	public static void start() throws IOException, SQLException {
		if(AOServDaemonConfiguration.isManagerEnabled(MySQLCreditCardScanner.class) && mySQLCreditCardScanner==null) {
			synchronized(System.out) {
				if(mySQLCreditCardScanner==null) {
					System.out.print("Starting MySQLCreditCardScanner: ");
					mySQLCreditCardScanner=new MySQLCreditCardScanner();
					CronDaemon.addCronJob(mySQLCreditCardScanner, LogFactory.getLogger(MySQLCreditCardScanner.class));
					System.out.println("Done");
				}
			}
		}
	}

	private static final Schedule schedule = (int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) ->
		minute==30
		&& hour==2
		&& dayOfWeek==Calendar.SUNDAY;

	@Override
	public Schedule getCronJobSchedule() {
		return schedule;
	}

	@Override
	public CronJobScheduleMode getCronJobScheduleMode() {
		 return CronJobScheduleMode.SKIP;
	}

	@Override
	public String getCronJobName() {
		return "MySQLCreditCardScanner";
	}

	/**
	 * Performs the scheduled task.
	 */
	@Override
	public void runCronJob(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
		scanMySQLForCards();
	}

	@Override
	public int getCronJobThreadPriority() {
		return Thread.NORM_PRIORITY-2;
	}

	public static void main(String[] args) {
		scanMySQLForCards();
	}

	private static void scanMySQLForCards() {
		try {
			AOServer thisAOServer=AOServDaemon.getThisAOServer();

			Map<Business,StringBuilder> reports = new HashMap<>();

			List<MySQLServer> mysqlServers = thisAOServer.getMySQLServers();
			for(MySQLServer mysqlServer : mysqlServers) {
				List<MySQLDatabase> mysqlDatabases = mysqlServer.getMySQLDatabases();
				for(MySQLDatabase database : mysqlDatabases) {
					MySQLDatabaseName name=database.getName();

					// Get connection to the database
					Class.forName(AOServDaemonConfiguration.getMySqlDriver()).newInstance();
					try (Connection conn = DriverManager.getConnection(
						"jdbc:mysql://"+thisAOServer.getPrimaryIPAddress().getInetAddress().toBracketedString()+":"+database.getMySQLServer().getNetBind().getPort().getPort()+"/"+name,
						AOServDaemonConfiguration.getMySqlUser(),
						AOServDaemonConfiguration.getMySqlPassword()
					)) {
						Business business = database.getPackage().getBusiness();
						StringBuilder report = reports.get(business);
						if(report==null) reports.put(business, report=new StringBuilder());
						scanForCards(thisAOServer, mysqlServer, database, conn, name.toString(), report);
					}
				}
			}
			for(Business business : reports.keySet()) {
				StringBuilder report = reports.get(business);
				if(report!=null && report.length()>0) {
					/* TODO
					AOServDaemon.getConnector().getThisBusinessAdministrator().addTicket(
						business,
						TicketType.TODO_SECURITY,
						report.toString(),
						Ticket.NO_DEADLINE,
						TicketPriority.HIGH,
						null,
						null,
						null,
						null,
						null
					);*/
				}
			}
		} catch(ThreadDeath td) {
			throw td;
		} catch(Throwable t) {
			LogFactory.getLogger(MySQLCreditCardScanner.class).log(Level.SEVERE, null, t);
		}
	}

	public static void scanForCards(AOServer aoServer, MySQLServer mysqlServer, MySQLDatabase database, Connection conn, String catalog, StringBuilder report) throws SQLException {
		DatabaseMetaData metaData = conn.getMetaData();
		String[] tableTypes = new String[] {"TABLE"};
		try (ResultSet tables = metaData.getTables(catalog, null, null, tableTypes)) {
			while(tables.next()) {
				String table = tables.getString(3);
				if(MySQLDatabase.isSafeName(table)) {
					StringBuilder buffer = new StringBuilder();
					buffer.append("select count(*) from `").append(table).append("` where ");
					try (ResultSet columns = metaData.getColumns(catalog, null, table, null)) {
						boolean isFirst = true;
						while(columns.next()) {
							String column = columns.getString(4);
							if(MySQLDatabase.isSafeName(column)) {
								if(isFirst) isFirst = false;
								else buffer.append(" OR ");

								buffer.append("(length(`").append(column).append("`)<25 && `").append(column).append("` regexp '^\\w*(");

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
									.append("Server........: ").append(aoServer.getHostname()).append('\n')
									.append("MySQL Server..: ").append(mysqlServer.toString()).append('\n')
									.append("Database......: ").append(database.getName()).append('\n')
									.append("Table.........: ").append(table).append('\n')
									.append("Column........: ").append(column).append('\n');
							}
						}
					}
					// Find total number of rows
					long rowCount;
					long ccCount;
					try (Statement stmt = conn.createStatement()) {
						try (ResultSet results = stmt.executeQuery("select count(*) from `"+table+"`")) {
							if(!results.next()) throw new SQLException("No results returned!");
							rowCount = results.getLong(1);
						}
						try (ResultSet cardnumbers = stmt.executeQuery(buffer.toString())) {
							if(!cardnumbers.next()) throw new SQLException("No results returned!");
							ccCount = cardnumbers.getLong(1);
						}
					}
					if(ccCount>50 && (ccCount*2)>=rowCount) {
						report.append('\n')
							.append("Credit cards found in database\n")
							.append('\n')
							.append("Server........: ").append(aoServer.getHostname()).append('\n')
							.append("MySQL Server..: ").append(mysqlServer.toString()).append('\n')
							.append("Database......: ").append(database.getName()).append('\n')
							.append("Table.........: ").append(table).append('\n')
							.append("Row Count.....: ").append(rowCount).append('\n')
							.append("Credit Cards..: ").append(ccCount).append('\n');
					}
				} else {
					report.append('\n')
						.append("Unable to scan table, unsafe name\n")
						.append('\n')
						.append("Server........: ").append(aoServer.getHostname()).append('\n')
						.append("MySQL Server..: ").append(mysqlServer.toString()).append('\n')
						.append("Database......: ").append(database.getName()).append('\n')
						.append("Table.........: ").append(table).append('\n');
				}
			}
		}
		// TODO: Scan for both PostgreSQL and MySQL here
		// TODO: Scan for both PostgreSQL and MySQL here
			}
}
