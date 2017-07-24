package vertx_pg;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// VertxPg.java
// Author: Ian Lewis ijl20@cam.ac.uk
//
// test code for Vertx Java PostgreSQL / PostGIS access
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.Handler;
import io.vertx.core.file.FileSystem;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sql.ResultSet;

import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

public class VertxPg extends AbstractVerticle {

    private final String VERSION = "0.03";
    
    private EventBus eb = null;

    SQLConnection sql_connection = null;

    @Override
    public void start(Future<Void> fut) throws Exception 
    {

        boolean ok = true; // simple boolean to flag an abort during startup

        log("VertxPg started");

        // create link to EventBus
        eb = vertx.eventBus();

        JsonObject sql_client_config = new JsonObject()
              .put("url", "jdbc:postgresql:ijl20")
              .put("user", "ijl20")
              .put("password", "foo!1")
              .put("driver_class", "org.postgresql.Driver");
        
    //SQLClient sql_client = PostgreSQLClient.createShared(vertx, sql_client_config);
        JDBCClient jdbc_client = JDBCClient.createShared(vertx, sql_client_config);

        log("VertxPg jdbc_client created.");

        jdbc_client.getConnection(res -> {
                if (res.failed()) {
                    fut.fail(res.cause());
                } else {
                    log("VertxPg getConnection succeeded.");

                    sql_connection = res.result();

                    String sql_query = "SELECT name,"+
                        "ST_X(location4d::geometry) AS lat,"+
                        "ST_Y(location4d::geometry) AS lng,"+
                        "ST_Z(location4d::geometry) AS alt,"+
                        "ST_M(location4d::geometry) AS ts,"+
                        "info FROM names";

                    sql_connection.query( sql_query,
                         r -> {
                                  if (r.failed()) 
                                  {
                                      fut.fail(r.cause());
                                      return;
                                  }

                                  log(r.result().getNumRows() + " rows returned");

                                  for (JsonObject row : r.result().getRows())
                                  {
                                      log(row.toString());
                                  }
/*
                                  row_stream
                                      .resultSetClosedHandler(v -> {
                                              // will ask to restart the stream with the new result set if any
                                              row_stream.moreResults();
                                          })
                                      .handler(row -> {
                                              // do something with each row...
                                              log("row size "+row.size());
                                          })
                                      .endHandler(v -> {
                                              // no more data available...
                                              log("row_stream ended");
                                          });
*/
                                  //log(qr.result().getResults().get(0).toString());
                                      
                                  // close connection to database
                                  sql_connection.close(v -> {
                                      log("VertxPg sql_connection closed.");
                                  });
                              });
                }
            });


    } // end start()

    // get current local time as "YYYY-MM-DD-hh-mm-ss"
    private String local_datetime_string()
    {
        LocalDateTime local_time = LocalDateTime.now();
        return local_time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
    }

    // print msg to stdout prepended with local time
    private void log(String msg)
    {
        System.out.println(local_datetime_string()+" "+msg);
    }


} // end VertxPg class
