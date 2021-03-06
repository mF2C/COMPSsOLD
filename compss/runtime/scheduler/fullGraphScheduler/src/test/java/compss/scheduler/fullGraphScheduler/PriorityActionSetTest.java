/*         
 *  Copyright 2002-2018 Barcelona Supercomputing Center (www.bsc.es)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package es.bsc.compss.scheduler.fullGraphScheduler;

import es.bsc.es.bsc.compss.scheduler.exceptions.BlockedActionException;
import es.bsc.es.bsc.compss.scheduler.exceptions.UnassignedActionException;
import es.bsc.es.bsc.compss.scheduler.fullGraphScheduler.FullGraphSchedulingInformation;
import es.bsc.es.bsc.compss.scheduler.fullGraphScheduler.utils.Verifiers;
import es.bsc.es.bsc.compss.scheduler.types.AllocatableAction;
import es.bsc.es.bsc.compss.scheduler.types.PriorityActionSet;
import es.bsc.es.bsc.compss.scheduler.types.fake.FakeActionOrchestrator;
import es.bsc.es.bsc.compss.scheduler.types.fake.FakeAllocatableAction;
import es.bsc.es.bsc.compss.scheduler.types.fake.FakeImplementation;
import es.bsc.es.bsc.compss.scheduler.types.fake.FakeProfile;
import es.bsc.es.bsc.compss.scheduler.types.fake.FakeResourceDescription;
import es.bsc.compss.types.implementations.Implementation;
import es.bsc.compss.util.CoreManager;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;

import org.junit.After;
import org.junit.AfterClass;
import static org.junit.Assert.fail;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;


public class PriorityActionSetTest {

    private static FullGraphScheduler<FakeProfile, FakeResourceDescription, FakeImplementation> ds;
    private static FakeActionOrchestrator fao;


    public PriorityActionSetTest() {
        ds = new FullGraphScheduler<FakeProfile, FakeResourceDescription, FakeImplementation>();
        fao = new FakeActionOrchestrator(ds);
        ds.setOrchestrator(fao);
    }

    @BeforeClass
    public static void setUpClass() {
        CoreManager.clear();
        CoreManager.registerNewCoreElement("fakeSignature00");
        CoreManager.registerNewCoreElement("fakeSignature10");
        CoreManager.registerNewCoreElement("fakeSignature20");

        FakeImplementation impl00 = new FakeImplementation(0, 0, new FakeResourceDescription(2));
        List<Implementation<?>> impls0 = new LinkedList<>();
        impls0.add(impl00);
        List<String> signatures0 = new LinkedList<>();
        signatures0.add("fakeSignature00");
        CoreManager.registerNewImplementations(0, impls0, signatures0);

        FakeImplementation impl10 = new FakeImplementation(1, 0, new FakeResourceDescription(3));
        List<Implementation<?>> impls1 = new LinkedList<>();
        impls1.add(impl10);
        List<String> signatures1 = new LinkedList<>();
        signatures1.add("fakeSignature10");
        CoreManager.registerNewImplementations(1, impls1, signatures1);

        FakeImplementation impl20 = new FakeImplementation(2, 0, new FakeResourceDescription(1));
        List<Implementation<?>> impls2 = new LinkedList<>();
        impls2.add(impl20);
        List<String> signatures2 = new LinkedList<>();
        signatures2.add("fakeSignature20");
        CoreManager.registerNewImplementations(2, impls2, signatures2);
    }

    @AfterClass
    public static void tearDownClass() {
    }

    @Before
    public void setUp() {

    }

    @After
    public void tearDown() {
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testInitialScheduling() throws BlockedActionException, UnassignedActionException {
        PriorityActionSet<FakeProfile, FakeResourceDescription, FakeImplementation> pas = new PriorityActionSet<>(
                new Comparator<AllocatableAction<FakeProfile, FakeResourceDescription, FakeImplementation>>() {

                    @Override
                    public int compare(AllocatableAction<FakeProfile, FakeResourceDescription, FakeImplementation> o1,
                            AllocatableAction<FakeProfile, FakeResourceDescription, FakeImplementation> o2) {
                        return Long.compare(o1.getId(), o2.getId());
                    }
                });

        PriorityQueue<AllocatableAction<FakeProfile, FakeResourceDescription, FakeImplementation>> peeks;

        FakeAllocatableAction action1 = new FakeAllocatableAction(fao, 1, 0, CoreManager.getCoreImplementations(0));
        ((FullGraphSchedulingInformation<FakeProfile, FakeResourceDescription, FakeImplementation>) action1.getSchedulingInfo())
                .setToReschedule(true);
        pas.offer(action1);
        if (action1 != pas.peek()) {
            fail(action1 + " expected to be the most prioritary action and " + pas.peek() + " was.");
        }
        FakeAllocatableAction action2 = new FakeAllocatableAction(fao, 1, 0, CoreManager.getCoreImplementations(0));
        ((FullGraphSchedulingInformation<FakeProfile, FakeResourceDescription, FakeImplementation>) action2.getSchedulingInfo())
                .setToReschedule(true);
        pas.offer(action2);
        if (action1 != pas.peek()) {
            fail(action1 + " expected to be the most prioritary action and " + pas.peek() + " was.");
        }
        FakeAllocatableAction action3 = new FakeAllocatableAction(fao, 3, 0, null);
        ((FullGraphSchedulingInformation<FakeProfile, FakeResourceDescription, FakeImplementation>) action3.getSchedulingInfo())
                .setToReschedule(true);
        pas.offer(action3);
        if (action1 != pas.peek()) {
            fail(action1 + " expected to be the most prioritary action and " + pas.peek() + " was.");
        }
        peeks = pas.peekAll();
        AllocatableAction<FakeProfile, FakeResourceDescription, FakeImplementation>[] expectedPeeks = new AllocatableAction[] { action1,
                action3 };
        Verifiers.verifyPriorityActions(peeks, expectedPeeks);

        FakeAllocatableAction action4 = new FakeAllocatableAction(fao, 4, 0, CoreManager.getCoreImplementations(1));
        ((FullGraphSchedulingInformation<FakeProfile, FakeResourceDescription, FakeImplementation>) action4.getSchedulingInfo())
                .setToReschedule(true);
        pas.offer(action4);
        peeks = pas.peekAll();
        expectedPeeks = new AllocatableAction[] { action1, action3, action4 };
        Verifiers.verifyPriorityActions(peeks, expectedPeeks);

        AllocatableAction<FakeProfile, FakeResourceDescription, FakeImplementation> action = pas.poll();
        if (action1 != action) {
            fail(action1 + " expected to be the most prioritary action and " + action + " was.");
        }
        peeks = pas.peekAll();
        expectedPeeks = new AllocatableAction[] { action2, action3, action4 };
        Verifiers.verifyPriorityActions(peeks, expectedPeeks);

        action = pas.poll();
        if (action2 != action) {
            fail(action2 + " expected to be the most prioritary action and " + action + " was.");
        }
        peeks = pas.peekAll();
        expectedPeeks = new AllocatableAction[] { action3, action4 };
        Verifiers.verifyPriorityActions(peeks, expectedPeeks);

        action = pas.poll();
        if (action3 != action) {
            fail(action3 + " expected to be the most prioritary action and " + action + " was.");
        }
        peeks = pas.peekAll();
        expectedPeeks = new AllocatableAction[] { action4 };
        Verifiers.verifyPriorityActions(peeks, expectedPeeks);

        action = pas.poll();
        if (action4 != action) {
            fail(action4 + " expected to be the most prioritary action and " + action + " was.");
        }
        peeks = pas.peekAll();
        expectedPeeks = new AllocatableAction[] {};
        Verifiers.verifyPriorityActions(peeks, expectedPeeks);

        FakeAllocatableAction action5 = new FakeAllocatableAction(fao, 5, 0, CoreManager.getCoreImplementations(1));
        ((FullGraphSchedulingInformation<FakeProfile, FakeResourceDescription, FakeImplementation>) action5.getSchedulingInfo())
                .setToReschedule(true);
        FakeAllocatableAction action6 = new FakeAllocatableAction(fao, 6, 0, CoreManager.getCoreImplementations(1));
        ((FullGraphSchedulingInformation<FakeProfile, FakeResourceDescription, FakeImplementation>) action6.getSchedulingInfo())
                .setToReschedule(true);
        pas.offer(action6);
        action = pas.peek();
        if (action6 != action) {
            fail(action6 + " expected to be the most prioritary action and " + action + " was.");
        }
        pas.offer(action5);
        action = pas.peek();
        if (action5 != action) {
            fail(action5 + " expected to be the most prioritary action and " + action + " was.");
        }
    }

}
