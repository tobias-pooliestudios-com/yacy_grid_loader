/**
 *  Loader
 *  Copyright 25.04.2017 by Michael Peter Christen, @0rb1t3r
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package net.yacy.grid.loader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.servlet.Servlet;

import org.json.JSONArray;
import org.json.JSONObject;

import ai.susi.mind.SusiAction;
import ai.susi.mind.SusiThought;
import net.yacy.grid.YaCyServices;
import net.yacy.grid.http.ClientIdentification;
import net.yacy.grid.loader.api.LoaderService;
import net.yacy.grid.loader.api.ProcessService;
import net.yacy.grid.loader.retrieval.ApacheHttpClient;
import net.yacy.grid.loader.retrieval.ContentLoader;
import net.yacy.grid.mcp.AbstractBrokerListener;
import net.yacy.grid.mcp.BrokerListener;
import net.yacy.grid.mcp.Data;
import net.yacy.grid.mcp.MCP;
import net.yacy.grid.mcp.Service;
import net.yacy.grid.tools.GitTool;
import net.yacy.grid.tools.Logger;
import net.yacy.grid.tools.Memory;

/**
 * The Loader main class
 *
 * performance debugging:
 * http://localhost:8200/yacy/grid/mcp/info/threaddump.txt
 * http://localhost:8200/yacy/grid/mcp/info/threaddump.txt?count=100 *
 */
public class Loader {

    private final static YaCyServices LOADER_SERVICE = YaCyServices.loader; // check with http://localhost:8200/yacy/grid/mcp/status.json
    private final static String DATA_PATH = "data";

    // define services
    @SuppressWarnings("unchecked")
    public final static Class<? extends Servlet>[] LOADER_SERVICES = new Class[]{
            // app services
            LoaderService.class,
            ProcessService.class
    };

    /**
     * broker listener, takes process messages from the queue "loader", "webloader"
     * i.e. test with:
     * curl -X POST -F "message=@job.json" -F "serviceName=loader" -F "queueName=webloader" http://yacygrid.com:8100/yacy/grid/mcp/messages/send.json
     * where job.json is:
{
  "metadata": {
    "process": "yacy_grid_loader",
    "count": 1
  },
  "data": [{
    "crawlingMode": "url",
    "crawlingURL": "http://yacy.net",
    "sitemapURL": "",
    "crawlingFile": "",
    "crawlingDepth": 3,
    "crawlingDepthExtension": "",
    "range": "domain",
    "mustmatch": ".*",
    "mustnotmatch": "",
    "ipMustmatch": ".*",
    "ipMustnotmatch": "",
    "indexmustmatch": ".*",
    "indexmustnotmatch": "",
    "deleteold": "off",
    "deleteIfOlderNumber": 0,
    "deleteIfOlderUnit": "day",
    "recrawl": "nodoubles",
    "reloadIfOlderNumber": 0,
    "reloadIfOlderUnit": "day",
    "crawlingDomMaxCheck": "off",
    "crawlingDomMaxPages": 1000,
    "crawlingQ": "off",
    "cachePolicy": "if fresh",
    "collection": "user",
    "agentName": "yacybot (yacy.net; crawler from yacygrid.com)",
    "user": "anonymous@nowhere.com",
    "client": "yacygrid.com"
  }],
  "actions": [{
    "type": "loader",
    "queue": "webloader",
    "urls": ["http://yacy.net"],
    "collection": "test",
    "targetasset": "test3/yacy.net.warc.gz",
    "actions": [{
      "type": "parser",
      "queue": "yacyparser",
      "sourceasset": "test3/yacy.net.warc.gz",
      "targetasset": "test3/yacy.net.jsonlist",
      "targetgraph": "test3/yacy.net.graph.json",
      "actions": [{
        "type": "indexer",
        "queue": "elasticsearch",
        "sourceasset": "test3/yacy.net.jsonlist"
      },{
        "type": "crawler",
        "queue": "webcrawler",
        "sourceasset": "test3/yacy.net.graph.json"
      }
      ]
    }]
  }]
}
     *
     * to check the queue content, see http://www.searchlab.eu:15672/
     */
    public static class LoaderListener extends AbstractBrokerListener implements BrokerListener {

        private final boolean disableHeadless;

        public LoaderListener(YaCyServices service, boolean disableHeadless) {
             super(service, Runtime.getRuntime().availableProcessors());
             this.disableHeadless = disableHeadless;
        }

        @Override
        public ActionResult processAction(SusiAction action, JSONArray data, String processName, int processNumber) {

            // check short memory status
            if (Memory.shortStatus()) {
                Logger.info(this.getClass(), "Loader short memory status: assigned = " + Memory.assigned() + ", used = " + Memory.used());
            }

            // find out if we should do headless loading
            String crawlID = action.getStringAttr("id");
            if (crawlID == null || crawlID.length() == 0) {
                Logger.info(this.getClass(), "Loader.processAction Fail: Action does not have an id: " + action.toString());
                return ActionResult.FAIL_IRREVERSIBLE;
            }
            JSONObject crawl = SusiThought.selectData(data, "id", crawlID);
            if (crawl == null) {
                Logger.info(this.getClass(), "Loader.processAction Fail: ID of Action not found in data: " + action.toString());
                return ActionResult.FAIL_IRREVERSIBLE;
            }
            int depth = action.getIntAttr("depth");
            int crawlingDepth = crawl.getInt("crawlingDepth");
            int priority =  crawl.has("priority") ? crawl.getInt("priority") : 0;
            boolean loaderHeadless = crawl.has("loaderHeadless") ? crawl.getBoolean("loaderHeadless") : true;
            if (this.disableHeadless) loaderHeadless = false;

            String targetasset = action.getStringAttr("targetasset");
            String threadnameprefix = processName + "-" + processNumber;
            Thread.currentThread().setName(threadnameprefix + " targetasset=" + targetasset);
            if (targetasset != null && targetasset.length() > 0) {
                ActionResult actionResult = ActionResult.SUCCESS;
                final byte[] b;
                try {
                    ContentLoader cl = new ContentLoader(action, data, targetasset.endsWith(".gz"), threadnameprefix, crawlID, depth, crawlingDepth, loaderHeadless, priority);
                    b = cl.getContent();
                    actionResult = cl.getResult();
                } catch (Throwable e) {
                    Logger.warn(this.getClass(), e);
                    return ActionResult.FAIL_IRREVERSIBLE;
                }
                if (actionResult == ActionResult.FAIL_IRREVERSIBLE) {
                    Logger.info(this.getClass(), "Loader.processAction FAILED processed message for targetasset " + targetasset);
                    return actionResult;
                }
                Logger.info(this.getClass(), "Loader.processAction SUCCESS processed message for targetasset " + targetasset);
                boolean storeToMessage = false; // debug version for now: always true TODO: set to false later
                try {
                    Data.gridStorage.store(targetasset, b);
                    Logger.info(this.getClass(), "Loader.processAction stored asset " + targetasset);
                } catch (Throwable e) {
                    Logger.warn(this.getClass(), "Loader.processAction asset " + targetasset + " could not be stored, carrying the asset within the next action", e);
                    storeToMessage = true;
                }
                if (storeToMessage) {
                    JSONArray actions = action.getEmbeddedActions();
                    actions.forEach(a ->
                        new SusiAction((JSONObject) a).setBinaryAsset(targetasset, b)
                    );
                    Logger.info(this.getClass(), "Loader.processAction stored asset " + targetasset + " into message");
                }
                Logger.info(this.getClass(), "Loader.processAction processed message from queue and stored asset " + targetasset);

                // success (has done something)
                return actionResult;
            }

            // fail (nothing done)
            return ActionResult.FAIL_IRREVERSIBLE;
        }
    }

    public static void main(String[] args) {
        // initialize environment variables
        List<Class<? extends Servlet>> services = new ArrayList<>();
        services.addAll(Arrays.asList(MCP.MCP_SERVICES));
        services.addAll(Arrays.asList(LOADER_SERVICES));
        Service.initEnvironment(LOADER_SERVICE, services, DATA_PATH, false);

        // initialize loader with user agent
        String userAgent = ClientIdentification.getAgent(ClientIdentification.googleAgentName/*.yacyInternetCrawlerAgentName*/).userAgent;
        String userAgentType = Data.config.get("grid.loader.userAgentType");
        if (userAgentType == null || userAgentType.length() == 0) userAgentType = "BROWSER";
        if ("CUSTOM".equals(userAgentType)) userAgent = Data.config.get("grid.lodeer.userAgentName");
        else if ("YACY".equals(userAgentType)) userAgent = ClientIdentification.yacyInternetCrawlerAgent.userAgent;
        else if ("GOOGLE".equals(userAgentType)) userAgent = ClientIdentification.getAgent(ClientIdentification.googleAgentName).userAgent;
        else userAgent = ClientIdentification.getAgent(ClientIdentification.browserAgentName).userAgent;
        ApacheHttpClient.initClient(userAgent);

        // start listener
        boolean disableHeadless = Data.config.containsKey("grid.loader.disableHeadless") ? Boolean.parseBoolean(Data.config.get("grid.loader.disableHeadless")) : false;
        BrokerListener brokerListener = new LoaderListener(LOADER_SERVICE, disableHeadless);
        new Thread(brokerListener).start();

        // start server
        Logger.info("Loader.main started Loader");
        Logger.info(new GitTool().toString());
        Service.runService(null);
        brokerListener.terminate();
    }

}
