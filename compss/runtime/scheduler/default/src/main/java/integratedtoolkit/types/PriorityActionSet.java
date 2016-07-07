package integratedtoolkit.types;

import integratedtoolkit.scheduler.defaultscheduler.DefaultSchedulingInformation;
import integratedtoolkit.scheduler.types.AllocatableAction;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.PriorityQueue;

public class PriorityActionSet {

    private PriorityQueue<AllocatableAction> noCoreActions;
    private PriorityQueue<AllocatableAction>[] coreActions;
    private PriorityQueue<AllocatableAction> priority;
    public final Comparator<AllocatableAction> comparator;

    public PriorityActionSet(Comparator<AllocatableAction> comparator) {
        this.comparator = comparator;
        noCoreActions = new PriorityQueue<AllocatableAction>(1, comparator);
        priority = new PriorityQueue<AllocatableAction>(1, comparator);
        coreActions = new PriorityQueue[0];
    }

    public PriorityActionSet(PriorityActionSet clone) {
        comparator = clone.comparator;
        noCoreActions = new PriorityQueue(clone.noCoreActions);
        coreActions = new PriorityQueue[clone.coreActions.length];
        for (int idx = 0; idx < coreActions.length; idx++) {
            coreActions[idx] = new PriorityQueue(clone.coreActions[idx]);
        }
        priority = new PriorityQueue<AllocatableAction>(clone.priority);
    }

    public void offer(AllocatableAction action) {
        Integer coreId = action.getCoreId();
        AllocatableAction currentPeek = null;
        if (coreId == null) {
            currentPeek = noCoreActions.peek();
            noCoreActions.offer(action);
        } else {
            if (coreId < coreActions.length) {
                currentPeek = coreActions[coreId].peek();
            } else {
                //Resize coreActions array
                int originalSize = this.coreActions.length;
                PriorityQueue<AllocatableAction>[] coreActions = new PriorityQueue[coreId + 1];
                System.arraycopy(this.coreActions, 0, coreActions, 0, originalSize);
                for (int coreIdx = originalSize; coreIdx < coreId + 1; coreIdx++) {
                    coreActions[coreIdx] = new PriorityQueue<AllocatableAction>(1, comparator);
                }
                this.coreActions = coreActions;
            }
            coreActions[coreId].offer(action);
        }
        if (currentPeek != action) {
            rebuildPriorityQueue();
        }
    }

    public AllocatableAction poll() {
        AllocatableAction currentPeek = priority.poll();
        if (currentPeek != null) {
            Integer coreId = currentPeek.getCoreId();
            AllocatableAction nextPeek;
            if (coreId == null) {
                noCoreActions.poll();
                nextPeek = noCoreActions.peek();
            } else {
                coreActions[coreId].poll();
                nextPeek = coreActions[coreId].peek();
            }
            if (nextPeek != null) {
                priority.offer(nextPeek);
            }
        }
        return currentPeek;
    }

    public AllocatableAction poll(long timeStamp) {
        AllocatableAction currentPeek = priority.peek();
        if (currentPeek != null) {
            DefaultSchedulingInformation dsi = ((DefaultSchedulingInformation) currentPeek.getSchedulingInfo());
            if (dsi.getExpectedStart() >= timeStamp) {
                return null;
            }
            priority.poll();
            Integer coreId = currentPeek.getCoreId();
            AllocatableAction nextPeek;
            if (coreId == null) {
                noCoreActions.poll();
                nextPeek = noCoreActions.peek();
            } else {
                coreActions[coreId].poll();
                nextPeek = coreActions[coreId].peek();
            }
            if (nextPeek != null) {
                priority.offer(nextPeek);
            }
        }
        return currentPeek;
    }

    public void removePeek(Integer coreId) {
        if (coreId == null) {
            noCoreActions.poll();
        } else {
            coreActions[coreId].poll();
        }

        rebuildPriorityQueue();
    }

    public AllocatableAction peek() {
        return priority.peek();
    }

    public AllocatableAction peek(long timeStamp) {
        AllocatableAction currentPeek = priority.peek();
        if (currentPeek != null) {
            DefaultSchedulingInformation dsi = ((DefaultSchedulingInformation) currentPeek.getSchedulingInfo());
            if (dsi.getExpectedStart() >= timeStamp) {
                currentPeek = null;
            }
        }
        return currentPeek;
    }

    public PriorityQueue<AllocatableAction> peekCores(LinkedList<Integer> cores) {
        PriorityQueue<AllocatableAction> peeks = new PriorityQueue(coreActions.length + 1, comparator);
        for (Integer core : cores) {
            AllocatableAction currentCore;
            if (cores == null) {
                currentCore = noCoreActions.peek();
            } else {
                currentCore = coreActions[core].peek();
            }
            if (currentCore != null) {
                peeks.offer(currentCore);
            }
        }
        return peeks;
    }

    public PriorityQueue<AllocatableAction> peekAll() {
        PriorityQueue<AllocatableAction> peeks = new PriorityQueue(coreActions.length + 1, comparator);
        AllocatableAction currentCore = noCoreActions.peek();
        if (currentCore != null) {
            peeks.offer(currentCore);
        }

        for (PriorityQueue<AllocatableAction> core : coreActions) {
            currentCore = core.peek();
            if (currentCore != null) {
                peeks.offer(currentCore);
            }
        }
        return peeks;
    }

    public PriorityQueue<AllocatableAction> peekAll(long timeStamp) {
        PriorityQueue<AllocatableAction> peeks = new PriorityQueue(coreActions.length + 1, comparator);
        AllocatableAction currentCore = noCoreActions.peek();
        if (currentCore != null) {
            DefaultSchedulingInformation dsi = ((DefaultSchedulingInformation) currentCore.getSchedulingInfo());
            if (dsi.getExpectedStart() < timeStamp) {
                peeks.offer(currentCore);
            }
        }
        for (PriorityQueue<AllocatableAction> core : coreActions) {
            currentCore = core.peek();
            if (currentCore != null) {
                DefaultSchedulingInformation dsi = ((DefaultSchedulingInformation) currentCore.getSchedulingInfo());
                if (dsi.getExpectedStart() < timeStamp) {
                    peeks.offer(currentCore);
                }
            }
        }
        return peeks;
    }

    private void rebuildPriorityQueue() {
        priority.clear();
        AllocatableAction action = noCoreActions.peek();
        if (action != null) {
            priority.offer(action);
        }
        for (PriorityQueue<AllocatableAction> coreAction : coreActions) {
            action = coreAction.peek();
            if (action != null) {
                priority.offer(action);
            }
        }
    }

    public int size() {
        int size = 0;
        size += noCoreActions.size();
        for (PriorityQueue pq : coreActions) {
            size += pq.size();
        }
        return size;
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public void remove(AllocatableAction action) {
        if (action.getCoreId() == null) {
            noCoreActions.remove(action);
        } else {
            coreActions[action.getCoreId()].remove(action);
        }
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("noCore -> ").append(noCoreActions).append("\n");
        for (int i = 0; i < coreActions.length; i++) {
            sb.append("Core ").append(i).append(" -> ").append(coreActions[i]).append("\n");
        }
        return sb.toString();
    }

}