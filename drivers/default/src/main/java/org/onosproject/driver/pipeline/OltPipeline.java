/*
 * Copyright 2016-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.driver.pipeline;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.onlab.osgi.ServiceDirectory;
import org.onlab.packet.EthType;
import org.onlab.packet.IPv4;
import org.onlab.packet.VlanId;
import org.onlab.util.KryoNamespace;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.behaviour.NextGroup;
import org.onosproject.net.behaviour.Pipeliner;
import org.onosproject.net.behaviour.PipelinerContext;
import org.onosproject.net.driver.AbstractHandlerBehaviour;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleOperations;
import org.onosproject.net.flow.FlowRuleOperationsContext;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criteria;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.EthTypeCriterion;
import org.onosproject.net.flow.criteria.IPCriterion;
import org.onosproject.net.flow.criteria.IPProtocolCriterion;
import org.onosproject.net.flow.criteria.PortCriterion;
import org.onosproject.net.flow.criteria.UdpPortCriterion;
import org.onosproject.net.flow.criteria.VlanIdCriterion;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.flow.instructions.L2ModificationInstruction;
import org.onosproject.net.flowobjective.FilteringObjective;
import org.onosproject.net.flowobjective.FlowObjectiveStore;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.NextObjective;
import org.onosproject.net.flowobjective.Objective;
import org.onosproject.net.flowobjective.ObjectiveError;
import org.onosproject.net.group.DefaultGroupBucket;
import org.onosproject.net.group.DefaultGroupDescription;
import org.onosproject.net.group.DefaultGroupKey;
import org.onosproject.net.group.Group;
import org.onosproject.net.group.GroupBucket;
import org.onosproject.net.group.GroupBuckets;
import org.onosproject.net.group.GroupDescription;
import org.onosproject.net.group.GroupEvent;
import org.onosproject.net.group.GroupKey;
import org.onosproject.net.group.GroupListener;
import org.onosproject.net.group.GroupService;
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.store.service.StorageService;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.Arrays;
import java.util.Objects;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Pipeliner for OLT device.
 */

public class OltPipeline extends AbstractHandlerBehaviour implements Pipeliner {

    private static final Integer QQ_TABLE = 1;
    private static final short MCAST_VLAN = 4000;
    private static final String OLTCOOKIES = "olt-cookies-must-be-unique";
    private static final int NO_ACTION_PRIORITY = 500;
    private final Logger log = getLogger(getClass());

    private ServiceDirectory serviceDirectory;
    private FlowRuleService flowRuleService;
    private GroupService groupService;
    private CoreService coreService;
    private StorageService storageService;

    private DeviceId deviceId;
    private ApplicationId appId;


    protected FlowObjectiveStore flowObjectiveStore;

    private Cache<GroupKey, NextObjective> pendingGroups;

    protected static KryoNamespace appKryo = new KryoNamespace.Builder()
            .register(KryoNamespaces.API)
            .register(GroupKey.class)
            .register(DefaultGroupKey.class)
            .register(OLTPipelineGroup.class)
            .build("OltPipeline");

    @Override
    public void init(DeviceId deviceId, PipelinerContext context) {
        log.debug("Initiate OLT pipeline");
        this.serviceDirectory = context.directory();
        this.deviceId = deviceId;

        flowRuleService = serviceDirectory.get(FlowRuleService.class);
        coreService = serviceDirectory.get(CoreService.class);
        groupService = serviceDirectory.get(GroupService.class);
        flowObjectiveStore = context.store();
        storageService = serviceDirectory.get(StorageService.class);

        appId = coreService.registerApplication(
                "org.onosproject.driver.OLTPipeline");


        pendingGroups = CacheBuilder.newBuilder()
                .expireAfterWrite(20, TimeUnit.SECONDS)
                .removalListener((RemovalNotification<GroupKey, NextObjective> notification) -> {
                    if (notification.getCause() == RemovalCause.EXPIRED) {
                        fail(notification.getValue(), ObjectiveError.GROUPINSTALLATIONFAILED);
                    }
                }).build();

        groupService.addListener(new InnerGroupListener());

    }

    @Override
    public void filter(FilteringObjective filter) {
        Instructions.OutputInstruction output;

        if (filter.meta() != null && !filter.meta().immediate().isEmpty()) {
            output = (Instructions.OutputInstruction) filter.meta().immediate().stream()
                    .filter(t -> t.type().equals(Instruction.Type.OUTPUT))
                    .limit(1)
                    .findFirst().get();

            if (output == null || !output.port().equals(PortNumber.CONTROLLER)) {
                log.warn("OLT can only filter packet to controller");
                fail(filter, ObjectiveError.UNSUPPORTED);
                return;
            }
        } else {
            fail(filter, ObjectiveError.BADPARAMS);
            return;
        }

        if (filter.key().type() != Criterion.Type.IN_PORT) {
            fail(filter, ObjectiveError.BADPARAMS);
            return;
        }

        EthTypeCriterion ethType = (EthTypeCriterion)
                filterForCriterion(filter.conditions(), Criterion.Type.ETH_TYPE);

        if (ethType == null) {
            fail(filter, ObjectiveError.BADPARAMS);
            return;
        }

        if (ethType.ethType().equals(EthType.EtherType.EAPOL.ethType())) {
            provisionEthTypeBasedFilter(filter, ethType, output);
        } else if (ethType.ethType().equals(EthType.EtherType.LLDP.ethType())) {
            provisionEthTypeBasedFilter(filter, ethType, output);

        } else if (ethType.ethType().equals(EthType.EtherType.IPV4.ethType())) {
            IPProtocolCriterion ipProto = (IPProtocolCriterion)
                    filterForCriterion(filter.conditions(), Criterion.Type.IP_PROTO);
            if (ipProto == null) {
                log.warn("OLT can only filter IGMP and DHCP");
                fail(filter, ObjectiveError.UNSUPPORTED);
                return;
            }
            if (ipProto.protocol() == IPv4.PROTOCOL_IGMP) {
                provisionIgmp(filter, ethType, ipProto, output);
            } else if (ipProto.protocol() == IPv4.PROTOCOL_UDP) {
                UdpPortCriterion udpSrcPort = (UdpPortCriterion)
                        filterForCriterion(filter.conditions(), Criterion.Type.UDP_SRC);

                UdpPortCriterion udpDstPort = (UdpPortCriterion)
                        filterForCriterion(filter.conditions(), Criterion.Type.UDP_DST);

                if (udpSrcPort.udpPort().toInt() != 68 || udpDstPort.udpPort().toInt() != 67) {
                    log.warn("OLT can only filter DHCP, wrong UDP Src or Dst Port");
                    fail(filter, ObjectiveError.UNSUPPORTED);
                }
                provisionDhcp(filter, ethType, ipProto, udpSrcPort, udpDstPort, output);
            } else {
                log.warn("OLT can only filter IGMP and DHCP");
                fail(filter, ObjectiveError.UNSUPPORTED);
            }
        } else {
            log.warn("\nOnly the following are Supported in OLT for filter ->\n"
                    + "ETH TYPE : EAPOL, LLDP and IPV4\n"
                    + "IPV4 TYPE: IGMP and UDP (for DHCP)");
            fail(filter, ObjectiveError.UNSUPPORTED);
        }

    }


    @Override
    public void forward(ForwardingObjective fwd) {

        if (checkForMulticast(fwd)) {
            processMulticastRule(fwd);
            return;
        }

        TrafficTreatment treatment = fwd.treatment();

        List<Instruction> instructions = treatment.allInstructions();

        Optional<Instruction> vlanInstruction = instructions.stream()
                .filter(i -> i.type() == Instruction.Type.L2MODIFICATION)
                .filter(i -> ((L2ModificationInstruction) i).subtype() ==
                        L2ModificationInstruction.L2SubType.VLAN_PUSH ||
                        ((L2ModificationInstruction) i).subtype() ==
                                L2ModificationInstruction.L2SubType.VLAN_POP)
                .findAny();


        if (!vlanInstruction.isPresent()) {
            installNoModificationRules(fwd);
        } else {
            L2ModificationInstruction vlanIns =
                    (L2ModificationInstruction) vlanInstruction.get();
            if (vlanIns.subtype() == L2ModificationInstruction.L2SubType.VLAN_PUSH) {
                installUpstreamRules(fwd);
            } else if (vlanIns.subtype() == L2ModificationInstruction.L2SubType.VLAN_POP) {
                installDownstreamRules(fwd);
            } else {
                log.error("Unknown OLT operation: {}", fwd);
                fail(fwd, ObjectiveError.UNSUPPORTED);
                return;
            }
        }

        pass(fwd);

    }


    @Override
    public void next(NextObjective nextObjective) {
        if (nextObjective.type() != NextObjective.Type.BROADCAST) {
            log.error("OLT only supports broadcast groups.");
            fail(nextObjective, ObjectiveError.BADPARAMS);
        }

        if (nextObjective.next().size() != 1) {
            log.error("OLT only supports singleton broadcast groups.");
            fail(nextObjective, ObjectiveError.BADPARAMS);
        }

        TrafficTreatment treatment = nextObjective.next().stream().findFirst().get();


        GroupBucket bucket = DefaultGroupBucket.createAllGroupBucket(treatment);
        GroupKey key = new DefaultGroupKey(appKryo.serialize(nextObjective.id()));


        pendingGroups.put(key, nextObjective);

        switch (nextObjective.op()) {
            case ADD:
                GroupDescription groupDesc =
                        new DefaultGroupDescription(deviceId,
                                                    GroupDescription.Type.ALL,
                                                    new GroupBuckets(Collections.singletonList(bucket)),
                                                    key,
                                                    null,
                                                    nextObjective.appId());
                groupService.addGroup(groupDesc);
                break;
            case REMOVE:
                groupService.removeGroup(deviceId, key, nextObjective.appId());
                break;
            case ADD_TO_EXISTING:
                groupService.addBucketsToGroup(deviceId, key,
                                               new GroupBuckets(Collections.singletonList(bucket)),
                                               key, nextObjective.appId());
                break;
            case REMOVE_FROM_EXISTING:
                groupService.removeBucketsFromGroup(deviceId, key,
                                                    new GroupBuckets(Collections.singletonList(bucket)),
                                                    key, nextObjective.appId());
                break;
            default:
                log.warn("Unknown next objective operation: {}", nextObjective.op());
        }


    }

    private void processMulticastRule(ForwardingObjective fwd) {
        if (fwd.nextId() == null) {
            log.error("Multicast objective does not have a next id");
            fail(fwd, ObjectiveError.BADPARAMS);
        }

        GroupKey key = getGroupForNextObjective(fwd.nextId());

        if (key == null) {
            log.error("Group for forwarding objective missing: {}", fwd);
            fail(fwd, ObjectiveError.GROUPMISSING);
        }

        Group group = groupService.getGroup(deviceId, key);
        TrafficTreatment treatment =
                buildTreatment(Instructions.createGroup(group.id()));

        FlowRule rule = DefaultFlowRule.builder()
                .fromApp(fwd.appId())
                .forDevice(deviceId)
                .forTable(0)
                .makePermanent()
                .withPriority(fwd.priority())
                .withSelector(fwd.selector())
                .withTreatment(treatment)
                .build();

        FlowRuleOperations.Builder builder = FlowRuleOperations.builder();
        switch (fwd.op()) {

            case ADD:
                builder.add(rule);
                break;
            case REMOVE:
                builder.remove(rule);
                break;
            case ADD_TO_EXISTING:
            case REMOVE_FROM_EXISTING:
                break;
            default:
                log.warn("Unknown forwarding operation: {}", fwd.op());
        }

        applyFlowRules(builder, fwd);

    }

    private boolean checkForMulticast(ForwardingObjective fwd) {

        IPCriterion ip = (IPCriterion) filterForCriterion(fwd.selector().criteria(),
                                                          Criterion.Type.IPV4_DST);

        if (ip == null) {
            return false;
        }

        return ip.ip().isMulticast();

    }

    private GroupKey getGroupForNextObjective(Integer nextId) {
        NextGroup next = flowObjectiveStore.getNextGroup(nextId);
        return appKryo.deserialize(next.data());

    }

    private void installNoModificationRules(ForwardingObjective fwd) {
        Instructions.OutputInstruction output = (Instructions.OutputInstruction) fetchOutput(fwd, "downstream");

        TrafficSelector selector = fwd.selector();

        Criterion inport = selector.getCriterion(Criterion.Type.IN_PORT);
        Criterion outerVlan = selector.getCriterion(Criterion.Type.VLAN_VID);
        Criterion innerVlan = selector.getCriterion(Criterion.Type.INNER_VLAN_VID);
        Criterion metadata;

        if (inport == null || output == null || innerVlan == null || outerVlan == null) {
            log.error("Forwarding objective is underspecified: {}", fwd);
            fail(fwd, ObjectiveError.BADPARAMS);
            return;
        }

        metadata = Criteria.matchMetadata(((VlanIdCriterion) innerVlan).vlanId().toShort());

        FlowRule.Builder outer = DefaultFlowRule.builder()
                .fromApp(fwd.appId())
                .forDevice(deviceId)
                .makePermanent()
                .withPriority(fwd.priority())
                .withSelector(buildSelector(inport, outerVlan, metadata))
                .withTreatment(buildTreatment(output));

        applyRules(fwd, outer);
    }

    private void installDownstreamRules(ForwardingObjective fwd) {
        Instructions.OutputInstruction output = (Instructions.OutputInstruction) fetchOutput(fwd, "downstream");

        if (output == null) {
            return;
        }

        TrafficSelector selector = fwd.selector();

        Criterion outerVlan = selector.getCriterion(Criterion.Type.VLAN_VID);
        Criterion innerVlanCriterion = selector.getCriterion(Criterion.Type.INNER_VLAN_VID);
        Criterion inport = selector.getCriterion(Criterion.Type.IN_PORT);

        if (outerVlan == null || innerVlanCriterion == null || inport == null) {
            log.error("Forwarding objective is underspecified: {}", fwd);
            fail(fwd, ObjectiveError.BADPARAMS);
            return;
        }

        VlanId innerVlan = ((VlanIdCriterion) innerVlanCriterion).vlanId();
        Criterion innerVid = Criteria.matchVlanId(innerVlan);

        long cvid = innerVlan.toShort();
        long outPort = output.port().toLong() & 0x0FFFFFFFFL;
        Criterion metadata = Criteria.matchMetadata((cvid << 32) | outPort);

        TrafficSelector outerSelector = buildSelector(inport, outerVlan, metadata);

        if (innerVlan.toShort() == VlanId.ANY_VALUE) {
            installDownstreamRulesForAnyVlan(fwd, output, outerSelector, buildSelector(inport,
                    Criteria.matchVlanId(VlanId.ANY)));
        } else {
            installDownstreamRulesForVlans(fwd, output, outerSelector, buildSelector(inport, innerVid));
        }
    }

    private void installDownstreamRulesForVlans(ForwardingObjective fwd, Instruction output,
                                                TrafficSelector outerSelector, TrafficSelector innerSelector) {

        List<Pair<Instruction, Instruction>> vlanOps =
                vlanOps(fwd,
                        L2ModificationInstruction.L2SubType.VLAN_POP);

        if (vlanOps == null || vlanOps.isEmpty()) {
            return;
        }

        Pair<Instruction, Instruction> popAndRewrite = vlanOps.remove(0);

        TrafficTreatment innerTreatment;
        VlanId setVlanId = ((L2ModificationInstruction.ModVlanIdInstruction) popAndRewrite.getRight()).vlanId();
        if (VlanId.NONE.equals(setVlanId)) {
            innerTreatment = (buildTreatment(true, output, popAndRewrite.getLeft(), fetchMeter(fwd),
                    fetchTransition(fwd)));
        } else {
            innerTreatment = (buildTreatment(true, output, popAndRewrite.getLeft(), popAndRewrite.getRight(),
                    fetchMeter(fwd), fetchTransition(fwd)));
        }

        //match: in port (nni), s-tag, metadata
        //action: pop vlan (s-tag), go to table 1
        FlowRule.Builder outer = DefaultFlowRule.builder()
                .fromApp(fwd.appId())
                .forDevice(deviceId)
                .makePermanent()
                .withPriority(fwd.priority())
                .withSelector(outerSelector)
                .withTreatment(buildTreatment(popAndRewrite.getLeft(),
                        Instructions.transition(QQ_TABLE)));

        //match: in port (nni), c-tag
        //action: deferred: output, immediate: pop, meter, go to 64 - 127 (technology profile)
        FlowRule.Builder inner = DefaultFlowRule.builder()
                .fromApp(fwd.appId())
                .forDevice(deviceId)
                .forTable(QQ_TABLE)
                .makePermanent()
                .withPriority(fwd.priority())
                .withSelector(innerSelector)
                .withTreatment(innerTreatment);
        applyRules(fwd, inner, outer);
    }

    private void installDownstreamRulesForAnyVlan(ForwardingObjective fwd, Instruction output,
                                                  TrafficSelector outerSelector, TrafficSelector innerSelector) {

        //match: in port (nni), s-tag and metadata
        //action: deferred: pop vlan (s-tag), immediate: go to table 1
        FlowRule.Builder outer = DefaultFlowRule.builder()
                .fromApp(fwd.appId())
                .forDevice(deviceId)
                .makePermanent()
                .withPriority(fwd.priority())
                .withSelector(outerSelector)
                .withTreatment(buildTreatment(true, Instructions.popVlan(),
                        Instructions.transition(QQ_TABLE)));

        //match: in port (nni) and s-tag
        //action: deferred: output, immediate : none, meter, go to 64 - 127 (technology profile)
        FlowRule.Builder inner = DefaultFlowRule.builder()
                .fromApp(fwd.appId())
                .forDevice(deviceId)
                .forTable(QQ_TABLE)
                .makePermanent()
                .withPriority(fwd.priority())
                .withSelector(innerSelector)
                .withTreatment(buildTreatment(true, output, fetchMeter(fwd), fetchTransition(fwd)));

        applyRules(fwd, inner, outer);
    }

    private void installUpstreamRules(ForwardingObjective fwd) {
        List<Pair<Instruction, Instruction>> vlanOps =
                vlanOps(fwd,
                        L2ModificationInstruction.L2SubType.VLAN_PUSH);

        if (vlanOps == null || vlanOps.isEmpty()) {
            return;
        }

        Instruction output = fetchOutput(fwd, "upstream");

        if (output == null) {
            return;
        }

        Pair<Instruction, Instruction> innerPair = vlanOps.remove(0);
        Pair<Instruction, Instruction> outerPair = vlanOps.remove(0);

        boolean noneValueVlanStatus = checkNoneVlanCriteria(fwd);
        boolean anyValueVlanStatus = checkAnyVlanMatchCriteria(fwd);

        if (anyValueVlanStatus) {
            installUpstreamRulesForAnyVlan(fwd, output, outerPair);
        } else {
            installUpstreamRulesForVlans(fwd, output, innerPair, outerPair, noneValueVlanStatus);
        }
    }

    private void installUpstreamRulesForVlans(ForwardingObjective fwd, Instruction output,
                                              Pair<Instruction, Instruction> innerPair,
                                              Pair<Instruction, Instruction> outerPair, Boolean noneValueVlanStatus) {

        TrafficTreatment innerTreatment;
        if (noneValueVlanStatus) {
            innerTreatment = buildTreatment(innerPair.getLeft(), innerPair.getRight(),
                    Instructions.transition(QQ_TABLE));
        } else {
            innerTreatment = buildTreatment(innerPair.getRight(), Instructions.transition(QQ_TABLE));
        }

        //match: in port, vlanId (0 or None)
        //action:
        //if vlanId None, push & set c-tag go to table 1
        //if vlanId 0 or any specific vlan, set c-tag go to table 1
        FlowRule.Builder inner = DefaultFlowRule.builder()
                .fromApp(fwd.appId())
                .forDevice(deviceId)
                .makePermanent()
                .withPriority(fwd.priority())
                .withSelector(fwd.selector())
                .withTreatment(innerTreatment);

        PortCriterion inPort = (PortCriterion)
                fwd.selector().getCriterion(Criterion.Type.IN_PORT);

        VlanId cVlanId = ((L2ModificationInstruction.ModVlanIdInstruction)
                innerPair.getRight()).vlanId();

        //match: in port, c-tag
        //action: deferred: output, immediate: push s-tag, meter, go to 64 - 127 (technology profile id)
        FlowRule.Builder outer = DefaultFlowRule.builder()
                .fromApp(fwd.appId())
                .forDevice(deviceId)
                .forTable(QQ_TABLE)
                .makePermanent()
                .withPriority(fwd.priority())
                .withSelector(buildSelector(inPort, Criteria.matchVlanId(cVlanId)))
                .withTreatment(buildTreatment(true, output, outerPair.getLeft(), outerPair.getRight(),
                        fetchMeter(fwd), fetchTransition(fwd)));

        applyRules(fwd, inner, outer);
    }

    private void installUpstreamRulesForAnyVlan(ForwardingObjective fwd, Instruction output,
                                                Pair<Instruction, Instruction> outerPair) {

        log.debug("Installing upstream rules for any value vlan");

        //match: in port and any-vlan (coming from OLT app.)
        //action: go to table 1
        FlowRule.Builder inner = DefaultFlowRule.builder()
                .fromApp(fwd.appId())
                .forDevice(deviceId)
                .makePermanent()
                .withPriority(fwd.priority())
                .withSelector(fwd.selector())
                .withTreatment(buildTreatment(Instructions.transition(QQ_TABLE)));


        TrafficSelector defaultSelector = DefaultTrafficSelector.builder()
                .matchInPort(((PortCriterion) fwd.selector().getCriterion(Criterion.Type.IN_PORT)).port())
                .build();

        //drop the packets that don't have vlan
        //match: in port
        //action: no action
        FlowRule.Builder defaultInner = DefaultFlowRule.builder()
                .fromApp(fwd.appId())
                .forDevice(deviceId)
                .makePermanent()
                .withPriority(NO_ACTION_PRIORITY)
                .withSelector(defaultSelector)
                .withTreatment(DefaultTrafficTreatment.emptyTreatment());

        Instruction qinqInstruction = Instructions.pushVlan(EthType.EtherType.QINQ.ethType());

        //match: in port and any-vlan (coming from OLT app.)
        //action: deferred: output, immediate: push:QinQ, vlanId (s-tag), meter, go to 64-127 (technology profile id)
        FlowRule.Builder outer = DefaultFlowRule.builder()
                .fromApp(fwd.appId())
                .forDevice(deviceId)
                .forTable(QQ_TABLE)
                .makePermanent()
                .withPriority(fwd.priority())
                .withSelector(fwd.selector())
                .withTreatment(buildTreatment(true, output, qinqInstruction, outerPair.getRight(),
                        fetchMeter(fwd), fetchTransition(fwd)));

        applyRules(fwd, inner, defaultInner, outer);
    }

    private boolean checkNoneVlanCriteria(ForwardingObjective fwd) {
        // Add the VLAN_PUSH treatment if we're matching on VlanId.NONE
        Criterion vlanMatchCriterion = filterForCriterion(fwd.selector().criteria(), Criterion.Type.VLAN_VID);
        boolean noneValueVlanStatus = false;
        if (vlanMatchCriterion != null) {
            noneValueVlanStatus = ((VlanIdCriterion) vlanMatchCriterion).vlanId().equals(VlanId.NONE);
        }
        return noneValueVlanStatus;
    }

    private boolean checkAnyVlanMatchCriteria(ForwardingObjective fwd) {
        Criterion anyValueVlanCriterion = fwd.selector().criteria().stream()
                .filter(c -> c.type().equals(Criterion.Type.VLAN_VID))
                .filter(vc -> ((VlanIdCriterion) vc).vlanId().toShort() == VlanId.ANY_VALUE)
                .findAny().orElse(null);

        if (anyValueVlanCriterion == null) {
            log.debug("Any value vlan match criteria is not found");
            return false;
        }

        return true;
    }

    private Instruction fetchOutput(ForwardingObjective fwd, String direction) {
        Instruction output = fwd.treatment().allInstructions().stream()
                .filter(i -> i.type() == Instruction.Type.OUTPUT)
                .findFirst().orElse(null);

        if (output == null) {
            log.error("OLT {} rule has no output", direction);
            fail(fwd, ObjectiveError.BADPARAMS);
            return null;
        }
        return output;
    }

    private Instruction fetchMeter(ForwardingObjective fwd) {
        Instruction meter = fwd.treatment().metered();

        if (meter == null) {
            log.debug("Meter instruction is not found for the forwarding objective {}", fwd);
            return null;
        }

        log.debug("Meter instruction is found.");
        return meter;
    }

    private Instruction fetchTransition(ForwardingObjective fwd) {
        Instruction transition = fwd.treatment().tableTransition();

        if (transition == null) {
            log.debug("Table / transition instruction is not found for the forwarding objective {}", fwd);
            return null;
        }

        log.debug("Transition instruction is found.");
        return transition;
    }

    private List<Pair<Instruction, Instruction>> vlanOps(ForwardingObjective fwd,
                                                         L2ModificationInstruction.L2SubType type) {

        List<Pair<Instruction, Instruction>> vlanOps = findVlanOps(
                fwd.treatment().allInstructions(), type);

        if (vlanOps == null || vlanOps.isEmpty()) {
            String direction = type == L2ModificationInstruction.L2SubType.VLAN_POP
                    ? "downstream" : "upstream";
            log.error("Missing vlan operations in {} forwarding: {}", direction, fwd);
            fail(fwd, ObjectiveError.BADPARAMS);
            return ImmutableList.of();
        }
        return vlanOps;
    }


    private List<Pair<Instruction, Instruction>> findVlanOps(List<Instruction> instructions,
                                                             L2ModificationInstruction.L2SubType type) {

        List<Instruction> vlanPushs = findL2Instructions(
                type,
                instructions);
        List<Instruction> vlanSets = findL2Instructions(
                L2ModificationInstruction.L2SubType.VLAN_ID,
                instructions);

        if (vlanPushs.size() != vlanSets.size()) {
            return ImmutableList.of();
        }

        List<Pair<Instruction, Instruction>> pairs = Lists.newArrayList();

        for (int i = 0; i < vlanPushs.size(); i++) {
            pairs.add(new ImmutablePair<>(vlanPushs.get(i), vlanSets.get(i)));
        }
        return pairs;
    }

    private List<Instruction> findL2Instructions(L2ModificationInstruction.L2SubType subType,
                                                 List<Instruction> actions) {
        return actions.stream()
                .filter(i -> i.type() == Instruction.Type.L2MODIFICATION)
                .filter(i -> ((L2ModificationInstruction) i).subtype() == subType)
                .collect(Collectors.toList());
    }

    private void provisionEthTypeBasedFilter(FilteringObjective filter,
                                             EthTypeCriterion ethType,
                                             Instructions.OutputInstruction output) {

        TrafficSelector selector = buildSelector(filter.key(), ethType);
        TrafficTreatment treatment = buildTreatment(output);
        buildAndApplyRule(filter, selector, treatment);

    }

    private void provisionIgmp(FilteringObjective filter, EthTypeCriterion ethType,
                               IPProtocolCriterion ipProto,
                               Instructions.OutputInstruction output) {
        TrafficSelector selector = buildSelector(filter.key(), ethType, ipProto);
        TrafficTreatment treatment = buildTreatment(output);
        buildAndApplyRule(filter, selector, treatment);
    }

    private void provisionDhcp(FilteringObjective filter, EthTypeCriterion ethType,
                               IPProtocolCriterion ipProto,
                               UdpPortCriterion udpSrcPort,
                               UdpPortCriterion udpDstPort,
                               Instructions.OutputInstruction output) {
        TrafficSelector selector = buildSelector(filter.key(), ethType, ipProto, udpSrcPort, udpDstPort);
        TrafficTreatment treatment = buildTreatment(output);
        buildAndApplyRule(filter, selector, treatment);
    }

    private void buildAndApplyRule(FilteringObjective filter, TrafficSelector selector,
                                   TrafficTreatment treatment) {
        FlowRule rule = DefaultFlowRule.builder()
                .fromApp(filter.appId())
                .forDevice(deviceId)
                .forTable(0)
                .makePermanent()
                .withSelector(selector)
                .withTreatment(treatment)
                .withPriority(filter.priority())
                .build();

        FlowRuleOperations.Builder opsBuilder = FlowRuleOperations.builder();

        switch (filter.type()) {
            case PERMIT:
                opsBuilder.add(rule);
                break;
            case DENY:
                opsBuilder.remove(rule);
                break;
            default:
                log.warn("Unknown filter type : {}", filter.type());
                fail(filter, ObjectiveError.UNSUPPORTED);
        }

        applyFlowRules(opsBuilder, filter);
    }

    private void applyRules(ForwardingObjective fwd, FlowRule.Builder... fwdBuilders) {
        FlowRuleOperations.Builder builder = FlowRuleOperations.builder();
        switch (fwd.op()) {
            case ADD:
                for (FlowRule.Builder fwdBuilder : fwdBuilders) {
                    builder.add(fwdBuilder.build());
                }
                break;
            case REMOVE:
                for (FlowRule.Builder fwdBuilder : fwdBuilders) {
                    builder.remove(fwdBuilder.build());
                }
                break;
            case ADD_TO_EXISTING:
                break;
            case REMOVE_FROM_EXISTING:
                break;
            default:
                log.warn("Unknown forwarding operation: {}", fwd.op());
        }

        applyFlowRules(builder, fwd);
    }

    private void applyFlowRules(FlowRuleOperations.Builder builder,
                                Objective objective) {
        flowRuleService.apply(builder.build(new FlowRuleOperationsContext() {
            @Override
            public void onSuccess(FlowRuleOperations ops) {
                pass(objective);
            }

            @Override
            public void onError(FlowRuleOperations ops) {
                fail(objective, ObjectiveError.FLOWINSTALLATIONFAILED);
            }
        }));
    }

    private Criterion filterForCriterion(Collection<Criterion> criteria, Criterion.Type type) {
        return criteria.stream()
                .filter(c -> c.type().equals(type))
                .limit(1)
                .findFirst().orElse(null);
    }

    private TrafficSelector buildSelector(Criterion... criteria) {


        TrafficSelector.Builder sBuilder = DefaultTrafficSelector.builder();

        for (Criterion c : criteria) {
            sBuilder.add(c);
        }

        return sBuilder.build();
    }

    private TrafficTreatment buildTreatment(boolean isDeferred, Instruction deferredInst, Instruction... instructions) {

        TrafficTreatment.Builder dBuilder = DefaultTrafficTreatment.builder();

        Arrays.stream(instructions).filter(Objects::nonNull).forEach(treatment -> dBuilder.add(treatment));

        if (isDeferred) {
            dBuilder.deferred();
            dBuilder.add(deferredInst);
        }

        return dBuilder.build();
    }

    private TrafficTreatment buildTreatment(Instruction... instructions) {


        TrafficTreatment.Builder tBuilder = DefaultTrafficTreatment.builder();

        for (Instruction i : instructions) {
            tBuilder.add(i);
        }

        return tBuilder.build();
    }


    private void fail(Objective obj, ObjectiveError error) {
        obj.context().ifPresent(context -> context.onError(obj, error));
    }

    private void pass(Objective obj) {
        obj.context().ifPresent(context -> context.onSuccess(obj));
    }


    private class InnerGroupListener implements GroupListener {
        @Override
        public void event(GroupEvent event) {
            if (event.type() == GroupEvent.Type.GROUP_ADDED ||
                    event.type() == GroupEvent.Type.GROUP_UPDATED) {
                GroupKey key = event.subject().appCookie();

                NextObjective obj = pendingGroups.getIfPresent(key);
                if (obj != null) {
                    flowObjectiveStore.putNextGroup(obj.id(), new OLTPipelineGroup(key));
                    pass(obj);
                    pendingGroups.invalidate(key);
                }
            }
        }
    }

    private static class OLTPipelineGroup implements NextGroup {

        private final GroupKey key;

        public OLTPipelineGroup(GroupKey key) {
            this.key = key;
        }

        public GroupKey key() {
            return key;
        }

        @Override
        public byte[] data() {
            return appKryo.serialize(key);
        }

    }

    @Override
    public List<String> getNextMappings(NextGroup nextGroup) {
        // TODO Implementation deferred to vendor
        return null;
    }
}
