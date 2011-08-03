/* Copyright 2011 Paremus Limited
 * 
 * Licensed under the Apache License, Version 2.0 (the License)
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License. 
 * see http://www.apache.org/licenses/LICENSE-2.0 
 */

package com.example.gateway.cli;

import static java.lang.System.out;

import com.example.gateway.Gateway;
import com.example.gateway.GatewayClient;
import com.example.gateway.messages.Connection;
import com.example.gateway.messages.Message;
import com.example.gateway.messages.QuoteRequest;
import org.apache.commons.cli.*;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.ServiceTracker;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class GatewayTestCLI {

    static final String scope = "gateway";

    static final String[] functions = new String[] { "testBatch" };

    private static final String UNDERLYING = "underlying";
    private static final String TEST_NAME = "testName";
    private static final String REQUEST_TIMEOUT = "requestTimeout";
    private static final String DISCOVERY_TIMEOUT = "discoveryTimeout";
    private static final String INDICATIVE = "indicative";
    private static final String VERBOSE = "verbose";
    private static final String BATCH_SIZE = "batchSize";
    private static final String BATCH_COUNT = "batchCount";

    private static final Options options;

    static {
        options = new Options();
        options.addOption(new Option("s", BATCH_SIZE, true, "Number of quotes in a batch"));
        options.addOption(new Option("c", BATCH_COUNT, true, "Number of batches to send"));
        options.addOption(new Option("d", DISCOVERY_TIMEOUT, true, "Timeout to discover gateway service"));
        options.addOption(new Option("t", REQUEST_TIMEOUT, true, "Timeout to receive responses"));

        options.addOption(new Option("u", UNDERLYING, true, "Underlying asset to quote on"));
        options.addOption(new Option("n", TEST_NAME, true, "Use specific listener name"));

        options.addOption(new Option("i", INDICATIVE, false, "Request indicative quotes"));
        options.addOption(new Option("v", VERBOSE, false, "Log verbose output to console"));
    }

    private ServiceTracker gatewayTracker;
    private BundleContext ctx;

    public GatewayTestCLI(ServiceTracker gatewayTracker, BundleContext ctx) {
        this.gatewayTracker = gatewayTracker;
        this.ctx = ctx;
    }

    public void testBatch(String[] xargs) throws InterruptedException, ParseException {
        CommandLine cmds = new PosixParser().parse(options, xargs);

        long discoveryTimeout = getLongOption(cmds, DISCOVERY_TIMEOUT, 5000L);
        long requestTimeout = getLongOption(cmds, REQUEST_TIMEOUT, 5000L);
        boolean verbose = cmds.hasOption(VERBOSE);
        boolean indicative = cmds.hasOption(INDICATIVE);
        int batchSize = getIntValue(cmds, BATCH_SIZE, 1000);
        int batchCount = getIntValue(cmds, BATCH_COUNT, 10);
        String underlying = getStringValue(cmds, UNDERLYING, "paremus");
        String testName = getStringValue(cmds, TEST_NAME, UUID.randomUUID().toString());

        Gateway gateway = (Gateway) gatewayTracker.waitForService(discoveryTimeout);

        if (gateway == null) {
            throw new IllegalStateException("Gateway service unavailable");
        }

        Semaphore testComplete = new Semaphore(1);
        testComplete.acquire();

        Semaphore connected = new Semaphore(1);
        connected.acquire();

        List<Collection<QuoteRequest>> requests = buildQuotes(batchSize, batchCount, underlying, indicative);

        ServiceRegistration reg = registerListener(testName, connected, testComplete, requests, verbose);

        Object loginToken = null;

        try {
            loginToken = gateway.connect(testName);

            connected.acquire();

            long start = System.currentTimeMillis();

            for(Collection<QuoteRequest> batch : requests) {
                gateway.request(loginToken, batch);            	
            }

            long sent = System.currentTimeMillis();

            if (verbose) {
                out.println("Waiting for test to complete");
            }

            boolean success = testComplete.tryAcquire(requestTimeout, TimeUnit.MILLISECONDS);

            if (!success) {
                throw new AssertionError("Failed to complete test in allotted time");
            }

            long complete = System.currentTimeMillis();

            long submitTime = (sent - start);
            long totalTime = (complete - start);
            long totalMsgs = batchSize * batchCount;

            out.println("Sent " + totalMsgs + " msgs");
            out.println("Submit time=" + submitTime + " ms");
            out.println("Submit rate=" + Math.round((totalMsgs * 1000.0)/(double) submitTime) + " msgs/sec");
            out.println("Roundtrip time=" + totalTime + " ms");
            out.println("Roundtrip rate=" + Math.round((totalMsgs * 1000.0)/(double) totalTime) + " msgs/sec");
        }
        finally {
            if (loginToken != null) {
                gateway.disconnect(loginToken);
            }

            reg.unregister();
        }
    }

    private int getIntValue(CommandLine cmds, String option, int defaultValue) {
        if (cmds.hasOption(option)) {
            return Integer.parseInt(cmds.getOptionValue(option));
        }
        else {
            return defaultValue;
        }
    }

    private String getStringValue(CommandLine cmds, String option, String defaultValue) {
        if (cmds.hasOption(option)) {
            return cmds.getOptionValue(option);
        }
        else {
            return defaultValue;
        }
    }

    private long getLongOption(CommandLine cmds, String option, long defaultValue) {
        if (cmds.hasOption(option)) {
            return Long.parseLong(cmds.getOptionValue(option));
        }
        else {
            return defaultValue;
        }
    }

    private ServiceRegistration registerListener(String name, final Semaphore connected, final Semaphore testComplete, List<Collection<QuoteRequest>> requests, final boolean verbose) {
        Hashtable<String, String> props = new Hashtable<String, String>();
        props.put(GatewayClient.ID, name);
        props.put("service.exported.interfaces", "*");

        final HashSet<Object> ids = new HashSet<Object>();

        for(Collection<QuoteRequest> batch : requests) {
            for (QuoteRequest r : batch) {
                ids.add(r.getId());
            }        	
        }

        GatewayClient client = new GatewayClient() {
            public void receive(Collection<Message> messages) {
                synchronized(ids) {
                    for (Message m : messages) {
                        if (m instanceof Connection) {
                            if (verbose) {
                                System.out.println("Connected");
                            }
                            connected.release();
                        }
                        else {
                            if (verbose) {
                                System.out.println("Received " + m);
                            }
                            ids.remove(m.getId());
                        }
                    }
                    if (ids.isEmpty()) {
                        testComplete.release();
                    }
                }
            }
        };

        return ctx.registerService(GatewayClient.class.getName(), client, props);
    }

    public List<Collection<QuoteRequest>> buildQuotes(int batchSize, int batchCount, String underlying, boolean indicative) {
        long seq = 0;

        ArrayList<Collection<QuoteRequest>> requests = new ArrayList<Collection<QuoteRequest>>(batchCount);
        for(int i = 0; i < batchCount; i++) {
            ArrayList<QuoteRequest> batch = new ArrayList<QuoteRequest>(batchSize);
            for(int j = 0; j < batchSize; j++) {
            	batch.add(new QuoteRequest(seq++, underlying, indicative));
            }
            requests.add(batch);
        }

        return requests;
    }
}
