package uk.ac.cam.tfc_server.msgrouter;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// MsgRouter.java
//
// This module receives messages from the EventBus and POSTs them on to application destinations
//
// Author: Ian Lewis ijl20@cam.ac.uk
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sql.ResultSet;

import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpClientRequest;

import java.io.*;
import java.net.URL;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;

import uk.ac.cam.tfc_server.util.Constants;
import uk.ac.cam.tfc_server.util.Log;

public class MsgRouter extends AbstractVerticle {

    private final String VERSION = "0.08";
    
    // from config()
    public int LOG_LEVEL;             // optional in config(), defaults to Constants.LOG_INFO
    private String MODULE_NAME;       // config module.name - normally "msgrouter"
    private String MODULE_ID;         // config module.id - unique for this verticle
    private String EB_SYSTEM_STATUS;  // config eb.system_status
    private String EB_MANAGER;        // config eb.manager
    
    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 25;
    private final int SYSTEM_STATUS_RED_SECONDS = 35;

    private EventBus eb = null;
    private Log logger;
        
    private ArrayList<JsonObject> START_ROUTERS; // config msgrouters.routers parameters

    // global vars
    private HashMap<String,HttpClient> http_clients; // used to store a HttpClient for each feed_id

    private HashMap<String,Sensor> sensors; // stores sensor_id -> destination_id mapping

    private HashMap<String,Destination> destinations; // stores destination_id -> http POST mapping

    @Override
    public void start(Future<Void> fut) throws Exception {
      
        // load initialization values from config()
        if (!get_config())
            {
                Log.log_err("MsgRouter."+ MODULE_ID + ": failed to load initial config()");
                vertx.close();
                return;
            }

        logger = new Log(LOG_LEVEL);
    
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": Version "+VERSION+" started with log_level "+LOG_LEVEL);

        eb = vertx.eventBus();

        // create holder for HttpClients, one per router
        http_clients = new HashMap<String,HttpClient>();

        // create holders for sensor and application data
        sensors = new HashMap<String,Sensor>();
        destinations = new HashMap<String,Destination>();

        vertx.executeBlocking(load_fut -> {
                    load_data(load_fut);
                },
                res -> { 
                    if (res.succeeded())
                    {
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                  " load_data() complete, status "+res.result());
                    }
                    else
                    {
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                  " load_data() failed, status "+res.cause());
                    }
                       });

        // iterate through all the routers to be started
        for (int i=0; i<START_ROUTERS.size(); i++)
            {
                start_router(START_ROUTERS.get(i));
            }

        // **********************************************************************************
        // Subscribe to 'manager' messages, e.g. to add sensors and destinations
        //
        // For the message to be processed by this module, it must be sent with this module's
        // MODULE_NAME and MODULE_ID in the "to_module_name" and "to_module_id" fields. E.g.
        // {
        //    "msg_type":    "module_method",
        //    "to_module_name": "msgrouter",
        //    "to_module_id": "test",
        //    "method": "add_sensor",
        //    "params": { "info": { "sensor_id": "0018b2000000113e",
        //                          "destination_id": "0018b2000000abcd"
        //                        }
        //              }
        // }
        eb.consumer(EB_MANAGER, message -> {
                manager_message(message);
            });

        // **********************************************************************************
        // send system status message from this module (i.e. to itself) immediately on startup, then periodically
        send_status();     
        // send periodic "system_status" messages
        vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> { send_status();  });

    } // end start()

    private boolean load_data(Future<Object> fut)
    {
        JsonObject sql_client_config = new JsonObject()
              .put("url", config().getString(MODULE_NAME+".db.url"))
              .put("user", config().getString(MODULE_NAME+".db.user"))
              .put("password", config().getString(MODULE_NAME+".db.password"))
              .put("driver_class", "org.postgresql.Driver");
        
        //SQLClient sql_client = PostgreSQLClient.createShared(vertx, sql_client_config);
        JDBCClient jdbc_client = JDBCClient.createShared(vertx, sql_client_config);

        logger.log(Constants.LOG_DEBUG, "VertxPg jdbc_client created for "+sql_client_config.getString("url"));

        jdbc_client.getConnection(res -> {
            if (res.failed()) 
            {
                logger.log(Constants.LOG_WARN, "VertxPg getConnection failed.");
                fut.fail(res.cause());
            }
            else 
            {
                logger.log(Constants.LOG_DEBUG, "VertxPg getConnection succeeded.");

                SQLConnection sql_connection = res.result();

                sql_connection.query( "SELECT info FROM csn_destination",
                     rd -> {
                              if (rd.failed()) 
                              {
                                  logger.log(Constants.LOG_WARN, "Failed query SELECT info FROM csn_destination");
                                  fut.fail(rd.cause());
                                  return;
                              }

                              logger.log(Constants.LOG_DEBUG, rd.result().getNumRows() + " rows returned from csn_destination");

                              for (JsonObject row : rd.result().getRows())
                              {
                                  //logger.log(Constants.LOG_DEBUG, row.toString());
                                  add_destination(new JsonObject(row.getString("info")));
                              }

                              sql_connection.query( "SELECT info FROM csn_sensor",
                                                    rs -> {
                                                        if (rs.failed()) 
                                                            {
                                                                logger.log(Constants.LOG_WARN, "Failed query SELECT info FROM csn_sensor");
                                                                fut.fail(rs.cause());
                                                                return;
                                                            }

                                                        logger.log(Constants.LOG_DEBUG, rs.result().getNumRows() + " rows returned from csn_sensor");

                                                        for (JsonObject row : rs.result().getRows())
                                                            {
                                                                //logger.log(Constants.LOG_DEBUG, row.toString());
                                                                add_sensor(new JsonObject(row.getString("info")));
                                                            }

                                                        // close connection to database
                                                        sql_connection.close(v -> {
                                                                logger.log(Constants.LOG_DEBUG, MODULE_NAME+" sql_connection closed.");
                                                                fut.complete("ok");
                                                            });
                                                    });
                          });
            }
        });

        return true;
    }

    // Here is where we process the 'manager' messages received for this module on the
    // config 'eb.manager' eventbus address.
    // e.g. the 'add_sensor' and 'add_application' messages.
    private void manager_message(Message<java.lang.Object> message)
    {
        JsonObject msg = new JsonObject(message.body().toString());

        // decode who this 'manager' message was sent to
        String to_module_name = msg.getString("to_module_name");
        String to_module_id = msg.getString("to_module_id");

        // *********************************************************************************
        // Skip this message if it has the wrong module_name/module_id
        if (to_module_name == null || !(to_module_name.equals(MODULE_NAME)))
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                       ": skipping manager message (not for this module_name) on "+EB_MANAGER);
            return;
        }
        if (to_module_id == null || !(to_module_id.equals(MODULE_ID)))
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                       ": skipping manager message (not for this module_id) on "+EB_MANAGER);
            return;
        }

        // *********************************************************************************
        // Process the manager message

        // ignore the message if it has no 'method' property
        String method = msg.getString("method");
        if (method == null)
        {
            logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                       ": skipping manager message ('method' property missing) on "+EB_MANAGER);
            return;
        }
        
        switch (method)
        {
            case Constants.METHOD_ADD_SENSOR:
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": received add_sensor manager message on "+EB_MANAGER);
                JsonObject sensor_info = msg.getJsonObject("params").getJsonObject("info");
                if (sensor_info == null)
                {
                    logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                               ": skipping manager message ('params' property missing) on "+EB_MANAGER);
                    return;
                }
                add_sensor(sensor_info);
                break;

            case Constants.METHOD_REMOVE_SENSOR:
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": received remove_sensor manager message on "+EB_MANAGER);
                sensor_info = msg.getJsonObject("params").getJsonObject("info");
                if (sensor_info == null)
                {
                    logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                               ": skipping manager message ('params' property missing) on "+EB_MANAGER);
                    return;
                }
                remove_sensor(sensor_info);
                break;

            case Constants.METHOD_ADD_DESTINATION:
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": received add_destination manager message on "+EB_MANAGER);
                JsonObject destination_info = msg.getJsonObject("params").getJsonObject("info");
                if (destination_info == null)
                {
                    logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                               ": skipping manager message ('params' property missing) on "+EB_MANAGER);
                    return;
                }
                add_destination(destination_info);
                break;

            case Constants.METHOD_REMOVE_DESTINATION:
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": received remove_destination manager message on "+EB_MANAGER);
                destination_info = msg.getJsonObject("params").getJsonObject("info");
                if (destination_info == null)
                {
                    logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                               ": skipping manager message ('params' property missing) on "+EB_MANAGER);
                    return;
                }
                remove_destination(destination_info);
                break;

            default:
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                           ": received unrecognized 'method' in manager message on "+EB_MANAGER+": "+method);
                break;
        }

    }

    // Add a sensor to sensors, having received an 'add_sensor' manager message
    private void add_sensor(JsonObject sensor_info)
    {
        String sensor_id = sensor_info.getString("sensor_id");
        if (sensor_id == null)
        {
            logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                       ": skipping add_sensor manager message ('sensor_id' property missing) on "+EB_MANAGER);
            return;
        }
        
        // create a Sensor object for this sensor
        Sensor sensor = new Sensor(sensor_info);

        // add the sensor to the current list (HashMap)
        sensors.put(sensor_id, sensor);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                   ": sensor count now "+sensors.size());
    }

    // Remove a LoraWAN sensor from sensors, having received a 'remove_sensor' manager message
    private void remove_sensor(JsonObject sensor_info)
    {
        String sensor_id = sensor_info.getString("sensor_id");
        if (sensor_id == null)
        {
            logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                       ": skipping remove_sensor manager message ('sensor_id' property missing) on "+EB_MANAGER);
            return;
        }
        
        // remove the sensor from the current list (HashMap)
        sensors.remove(sensor_id);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                   ": sensor count now "+sensors.size());
    }

    // Add a destination (destination_id, http.token, url) to destinations, having received an 'add_destination' manager message
    private void add_destination(JsonObject destination_info)
    {
        String destination_id = json_property_to_string(destination_info, "destination_id");

        if (destination_id == null)
        {
            logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                       ": skipping add_destination manager message ('destination_id' property missing) on "+EB_MANAGER);
            return;
        }

        try
        {
            // Create Destination object for this destination
            Destination destination = new Destination(destination_info);

            // Add to the current list (HashMap) of objects
            destinations.put(destination_id, destination);
        }
        catch (MalformedURLException e)
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                   ": Bad URL for destination "+destination_id);
            return;
        }
        
        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                   ": destination count now "+destinations.size());
    }

    private void remove_destination(JsonObject destination_info)
    {
        String destination_id = json_property_to_string(destination_info, "destination_id");

        if (destination_id == null)
        {
            logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                       ": skipping remove_destination manager message ('destination_id' property missing) on "+EB_MANAGER);
            return;
        }

        // Remove from the current list (HashMap) of objects
        destinations.remove(destination_id);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                   ": destination count now "+destinations.size());
    }
        
    // Return a String value for a JSONObject property that may be String or Integer.
    // This is used to bridge versions of tfc_web that may use either for 'destination_id'
    private String json_property_to_string(JsonObject jo, String property)
    {
        String string_value;
        try
        {
            string_value = jo.getString(property);
        }
        catch (java.lang.ClassCastException e)
        {
            string_value = jo.getInteger(property).toString();
        }
        return string_value;
    }

    // send UP status to the EventBus
    private void send_status()
    {
        eb.publish(EB_SYSTEM_STATUS,
                 "{ \"module_name\": \""+MODULE_NAME+"\"," +
                   "\"module_id\": \""+MODULE_ID+"\"," +
                   "\"status\": \"UP\"," +
                   "\"status_msg\": \"UP\"," +
                   "\"status_amber_seconds\": "+String.valueOf( SYSTEM_STATUS_AMBER_SECONDS ) + "," +
                   "\"status_red_seconds\": "+String.valueOf( SYSTEM_STATUS_RED_SECONDS ) +
                 "}" );
    }
    
    // ************************************************************
    // start_router()
    // start a Router by registering a consumer to the given address
    // ************************************************************
    private void start_router(JsonObject router_config)
    {

        // A router config() contains a minimum of a "source_address" property,
        // which is the EventBus address it will listen to for messages to be forwarded.
        //
        // Note: a router config() (in msgrouter.routers) MAY contain a filter, such as
        // "source_filter": { 
        //                    "field": "sensor_id",
        //                    "compare": "=",
        //                    "value": "0018b2000000113e"
        //                   }
        // in which case only messages on the source_address that match this pattern will
        // be processed.

        JsonObject filter_json = router_config.getJsonObject("source_filter");
        boolean has_filter =  filter_json != null;

        //final RouterFilter source_filter = has_filter ? new RouterFilter(filter_json) : null;

        final String config_destination_id = router_config.getString("destination_id");

        if (config_destination_id != null)
        {
            add_destination(router_config);
        }

        //final HttpClient http_client = vertx.createHttpClient( new HttpClientOptions()
        //                                               .setSsl(router_config.getBoolean("http.ssl"))
        //                                               .setTrustAll(true)
        //                                               .setDefaultPort(router_config.getInteger("http.port"))
        //                                               .setDefaultHost(router_config.getString("http.host"))
        //                                                     );
        String router_filter_text;
        if (has_filter)
            {
                //source_filter = new RouterFilter(filter_json);  
                router_filter_text = " with " + filter_json.toString();
            }
        else
            {
                router_filter_text = "";
            }
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
                   ": starting router "+router_config.getString("source_address")+ router_filter_text);

        // register to router_config.source_address,
        // test messages with router_config.source_filter
        // and call store_msg if current message passes filter
        eb.consumer(router_config.getString("source_address"), message -> {
            //System.out.println("MsgRouter."+MODULE_ID+": got message from " + router_config.source_address);
            JsonObject msg = new JsonObject(message.body().toString());
            
            //**************************************************************************
            //**************************************************************************
            // Route the message onwards via POST to destination
            //**************************************************************************
            //**************************************************************************
            if (!has_filter)// || source_filter.match(msg))
            {
                // route this message if it matches the filter within the RouterConfig
                //route_msg(http_client, router_config, msg);
                if (config_destination_id != null)
                {
                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": sending message via config destination_id "+config_destination_id);
                    try 
                    {
                        // Careful here!! Although FeedHandler(etc) can send an Array of data points in
                        // the "request_data" parameter, for LoraWAN purposes we are currently assuming
                        // only a single data value is going to be present, hence we are forwarding
                        // msg.getJsonArray("request_data").getJsonObject(0), not the whole array.
                        destinations.get(config_destination_id).send(msg.getJsonArray("request_data").getJsonObject(0).toString());
                    }
                    catch (Exception e)
                    {
                        logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                   ": send error for "+config_destination_id);
                    }
                    return;
                }
                else
                {
                    // There is no destination_id defined in the config(), so we'll try and route via
                    // the sensor_id -> destination_id mapping in the sensors HashMap
                    String dev_eui = msg.getString("dev_eui");
                    if (dev_eui == null)
                    {
                        logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                   ": skipping message (no dev_eui) ");
                        return;
                    }
                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": using dev_eui "+dev_eui);

                    String destination_id;

                    try
                    {
                        destination_id = json_property_to_string(sensors.get(dev_eui).info, "destination_id");
                    }
                    catch (Exception NullPointerException)
                    {
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                   ": ignoring "+dev_eui+" no destination_id set");
                        return;
                    }

                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": sending to destination_id "+destination_id);

                    destinations.get(destination_id).send(msg.getJsonArray("request_data").getJsonObject(0).toString());
                }
            }
            else
            {
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                     ": msg skipped no match "+router_config.getJsonObject("source_filter").toString());
            }

        });
    
    } // end start_router

    //**************************************************************************
    //**************************************************************************
    // Load initialization global constants defining this MsgRouter from config()
    //**************************************************************************
    //**************************************************************************
    private boolean get_config()
    {
        // config() values needed by all TFC modules are:
        //   module.name - usually "msgrouter"
        //   module.id - unique module reference to be used by this verticle
        //   eb.system_status - String eventbus address for system status messages
        //   eb.manager - eventbus address for manager messages
        
        MODULE_NAME = config().getString("module.name");
        if (MODULE_NAME == null)
        {
          Log.log_err("MsgRouter: module.name config() not set");
          return false;
        }
        
        MODULE_ID = config().getString("module.id");
        if (MODULE_ID == null)
        {
          Log.log_err("MsgRouter: module.id config() not set");
          return false;
        }

        LOG_LEVEL = config().getInteger(MODULE_NAME+".log_level", 0);
        if (LOG_LEVEL==0)
            {
                LOG_LEVEL = Constants.LOG_INFO;
            }
        
        EB_SYSTEM_STATUS = config().getString("eb.system_status");
        if (EB_SYSTEM_STATUS == null)
        {
          Log.log_err("MsgRouter."+MODULE_ID+": eb.system_status config() not set");
          return false;
        }

        EB_MANAGER = config().getString("eb.manager");
        if (EB_MANAGER == null)
        {
          Log.log_err("MsgRouter."+MODULE_ID+": eb.manager config() not set");
          return false;
        }

        // iterate through the msgrouter.routers config values
        START_ROUTERS = new ArrayList<JsonObject>();
        JsonArray config_router_list = config().getJsonArray(MODULE_NAME+".routers");
        for (int i=0; i<config_router_list.size(); i++)
            {
                JsonObject config_json = config_router_list.getJsonObject(i);

                // add MODULE_NAME, MODULE_ID to every RouterConfig
                config_json.put("module_name", MODULE_NAME);
                config_json.put("module_id", MODULE_ID);
                
                //RouterConfig router_config = new RouterConfig(config_json);
                
                START_ROUTERS.add(config_json);
            }

        return true;
    } // end get_config()

    // This class holds the sensor data
    // received in the 'params' property of the 'add_sensor' eventbus method message
    private class Sensor {
        public String sensor_id;
        public JsonObject info;
        // e.g. {
        //        "sensor_id": "0018b2000000113e",
        //        "destination_id": "0018b2000000abcd"
        //      }

        // Constructor
        Sensor(JsonObject sensor_info)
        {
            sensor_id = sensor_info.getString("sensor_id");
            info = sensor_info;
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                 ": added sensor "+this.toString());
        }

        public String toString()
        {
            return sensor_id + " -> " + json_property_to_string(info, "destination_id");
        }
    }

    // This class holds the LoraWAN destination (i.e. http destination) data
    // received in the 'params' property of the 'add_destination' eventbus method message
    private class Destination {
        public String destination_id;
        public JsonObject info;
        public HttpClient http_client;

        private class UrlParts {
            public boolean http_ssl;
            public int     http_port;
            public String  http_host;
            public String  http_path;
        }

        // { "destination_id": "xyz",
        //   "http_token":"foo!bar",
        //   "url": "http://localhost:8080/efgh"
        // }

        // Constructor
        // Here is where we create a new Destination on receipt of a "add_destination" message or
        // loading rows from database table csn_destinations
        //
        Destination(JsonObject destination_info) throws MalformedURLException
        {
            // destination_id is the definitive key
            // Will be used as lookup in "destinations" HashMap
            destination_id = json_property_to_string(destination_info, "destination_id");

            // And store entire Json payload into "info"
            info = destination_info;
            
            // The user originally gave a URL, which could be malformed, if so this will throw an
            // exception
            UrlParts u = parse_url(destination_info.getString("url"));

            // inject http_path into the destination "info"
            info.put("http_path", u.http_path);
            
            http_client = vertx.createHttpClient( new HttpClientOptions()
                                                       .setSsl(u.http_ssl)
                                                       .setTrustAll(true)
                                                       .setDefaultPort(u.http_port)
                                                       .setDefaultHost(u.http_host)
                                                );

            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                 ": added destination "+this.toString());
        }

        // Parse the string url into it's constituent parts for createHttpClientOptions
        private UrlParts parse_url(String url_string) throws MalformedURLException
        {
            URL url = new URL(url_string);
            
            UrlParts u = new UrlParts();
            
            u.http_ssl = url.getProtocol().equals("https");
            
            u.http_port = url.getPort();
            if (u.http_port < 0)
            {
                u.http_port = u.http_ssl ? 443 : 80;
            }
            
            u.http_host = url.getHost();

            u.http_path = url.getPath();
            
            return u;
        }
        
        public String toString()
        {
            String http_token = info.getString("http_token","");

            UrlParts u = null;
            
            try
            {
                u = parse_url(info.getString("url"));
            }
            catch (MalformedURLException e)
            {
                return "bad URL";
            }
            
            return destination_id+" -> "+
                   "<"+http_token+"> "+
                   (u.http_ssl ? "https://" : "http://")+
                   u.http_host +":"+
                   u.http_port +
                   u.http_path;
        }

        public void send(String msg)
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                       ": sending to "+destination_id+": " + msg);

            String http_uri = info.getString("http.uri");

            try
            {
                HttpClientRequest request = http_client.post(http_uri, response -> {
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                   ": msg posted to " + this.toString());

                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                   ": response was " + response.statusCode());

                    });

                request.exceptionHandler( e -> {
                        logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                   ": Destination HttpClientRequest error for "+destination_id);
                        });

                // Now do stuff with the request
                request.putHeader("content-type", "application/json");
                request.setTimeout(15000);

                String auth_token = info.getString("http.token");
                if (auth_token != null)
                    {
                        request.putHeader("X-Auth-Token", auth_token);
                    }
                // Make sure the request is ended when you're done with it
                request.end(msg);
            }
            catch (Exception e)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                           ": Destination send error for "+destination_id);
            }
        }
            
    } // end class Destination


} // end class MsgRouter

