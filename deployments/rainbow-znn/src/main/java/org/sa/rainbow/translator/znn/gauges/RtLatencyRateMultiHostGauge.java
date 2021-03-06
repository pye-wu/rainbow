/*
 * The MIT License
 *
 * Copyright 2014 CMU ABLE Group.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
/**
 * Created April 10, 2007.
 */
package org.sa.rainbow.translator.znn.gauges;

import org.sa.rainbow.core.error.RainbowException;
import org.sa.rainbow.core.gauges.RegularPatternGauge;
import org.sa.rainbow.core.models.commands.IRainbowOperation;
import org.sa.rainbow.core.util.TypedAttribute;
import org.sa.rainbow.core.util.TypedAttributeWithValue;
import org.sa.rainbow.translator.znn.probes.PingRTTProbe;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gauge for computing rate of change of roundtrip latency for N KB data using
 * Ping RTT data (PingRTTProbe) between Gauge's host and a list of target hosts.
 * 
 * @author Shang-Wen Cheng (zensoul@cs.cmu.edu)
 */
public class RtLatencyRateMultiHostGauge extends RegularPatternGauge {

    public static final String NAME = "G - Latency Rate-of-Change";
    /** Sample window to compute an average latency */
    public static final int AVG_SAMPLE_WINDOW = 5;
    /** Sample window to compute latency rate, roughly half of window for average */
    public static final int RATE_SAMPLE_WINDOW = 3;
    /** Standard Ping request size */
    public static final int PING_SIZE = PingRTTProbe.PING_REQ_SIZE;
    /** The estimated size of data we'll use to compute roundtrip latency */
    public static final int LATENCY_DATA_SIZE = 1024;  // one KB

    /** List of values reported by this Gauge */
    private static final String[] valueNames = {
            "latencyRate(*)"
    };
    private static final String DEFAULT = "DEFAULT";

    private Map<String,Queue<Double>> m_historyMap = null;
    private Map<String,Double> m_cumulationMap = null;
    private Map<String,Double> m_offsetCumulationMap = null;
    private Map<String,Queue<Double>> m_rateHistMap = null;
    private Map<String,Double> m_rateCumuMap = null;

    /**
     * Main constructor.
     * 
     * @throws RainbowException
     */
    public RtLatencyRateMultiHostGauge (String threadName, String id, long beaconPeriod, TypedAttribute gaugeDesc,
            TypedAttribute modelDesc, List<TypedAttributeWithValue> setupParams,
            Map<String, IRainbowOperation> mappings)
                    throws RainbowException {

        super(NAME, id, beaconPeriod, gaugeDesc, modelDesc, setupParams, mappings);

        m_historyMap = new HashMap<> ();
        m_cumulationMap = new HashMap<> ();
        m_offsetCumulationMap = new HashMap<> ();
        m_rateHistMap = new HashMap<> ();
        m_rateCumuMap = new HashMap<> ();

        addPattern(DEFAULT, Pattern.compile("\\[(.+)\\]\\s+(.+?):([0-9.]+)[/]([0-9.]+)[/]([0-9.]+)"));
    }

    /* (non-Javadoc)
     * @see org.sa.rainbow.translator.gauges.RegularPatternGauge#doMatch(java.lang.String, java.util.regex.Matcher)
     */
    @Override
    protected void doMatch (String matchName, Matcher m) {
        if (matchName == DEFAULT) {
            // acquire the next set of ping RTT data, we care for the average
//			String tstamp = m.group(1);
            String host = m.group(2);
            if (host.equals("")) return;
//			double msMin = Double.parseDouble(m.group(3));
            double msAvg = Double.parseDouble(m.group(4));
//			double msMax = Double.parseDouble(m.group(5));
            double bwBPS = PING_SIZE /*B*/ * 1000 /*ms/s*/ / msAvg /*ms*/;
            double latency = LATENCY_DATA_SIZE / bwBPS;

            // setup data struct for host if new
            if (! m_historyMap.containsKey(host)) {
                m_historyMap.put(host, new LinkedList<Double>());
                m_cumulationMap.put(host, 0.0);
                m_offsetCumulationMap.put(host, 0.0);
                m_rateHistMap.put(host, new LinkedList<Double>());
                m_rateCumuMap.put(host, 0.0);
            }
            Queue<Double> history = m_historyMap.get(host);
            double cumulation = m_cumulationMap.get(host);
            double offsetCumulation = m_offsetCumulationMap.get(host);
            // add latency value to cumulation and enqueue
            m_offsetCumulationMap.put(host, cumulation);  // store previous as offset
            cumulation += latency;
            history.offer(latency);
            if (history.size() > AVG_SAMPLE_WINDOW) {
                // if queue size reached window size, then
                //   dequeue and delete oldest value and report average
                cumulation -= history.poll();
            }
            m_cumulationMap.put(host, cumulation);  // store updated cumulation
            if (offsetCumulation == 0) return;  // most likely no history yet

            double rateOfChange = (cumulation - offsetCumulation) / offsetCumulation;
            Queue<Double> rateHist = m_rateHistMap.get(host);
            double rateCumu = m_rateCumuMap.get(host);
            rateCumu += rateOfChange;
            rateHist.offer(rateOfChange);
            if (rateHist.size() > RATE_SAMPLE_WINDOW) {
                rateCumu -= rateHist.poll();
            }
            m_rateCumuMap.put(host, rateCumu);
            rateOfChange = rateCumu / rateHist.size();
            m_reportingPort.trace (getComponentType (),
                    id () + ": " + cumulation + ", hist" + Arrays.toString (history.toArray ()));
            m_reportingPort.trace (getComponentType (),
                    id () + ": " + rateCumu + ", hist" + Arrays.toString (rateHist.toArray ()));

            // update connection in model with latency in seconds
            for (String valueName : valueNames) {
                // massage value name for mapping purposes
                valueName = valueName.replace("*", host);
                if (m_commands.containsKey (valueName)) {
                    // ZNewsSys.conn0.latency
                    IRainbowOperation cmd = getCommand (valueName);
                    Map<String, String> parameterMap = new HashMap<> ();
                    parameterMap.put (cmd.getParameters ()[0], Double.toString (rateOfChange));
                    issueCommand (cmd, parameterMap);
                }
            }
        }
    }

}
