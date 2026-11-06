package adris.altoclef.eventbus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * A static class to solve dependency issues. Lets us send and receive events globally, decoupling our codebase.
 * <p>
 * Technically `ConfigHelper` does something like this, but here is a more general case.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class EventBus {

    private record Pending(Class type, Subscription sub) {
    }

    private static final HashMap<Class, List<Subscription>> _topics = new HashMap<>();
    private static final List<Pending> _toAdd = new ArrayList<>();
    // subscriptions that said delete while a publish was iterating over them
    private static final List<Subscription> _toDelete = new ArrayList<>();
    // how many publishes are on the stack. a subscriber can publish again (and a publish can subscribe), the lists
    // only get touched when the outermost one is done
    private static int _depth;
    // where a throwing subscriber goes. the bridge points this at its error counter, the default just logs
    private static Consumer<Throwable> _errorHandler = t -> {
        System.err.println("an event subscriber threw");
        t.printStackTrace();
    };

    public static <T> void publish(T event) {
        Class type = event.getClass();

        // outermost publish only, nested ones are iterating the very lists these would modify
        if (_depth == 0) {
            applyPending();
        }

        List<Subscription> subscribers = _topics.get(type);
        if (subscribers == null) {
            return;
        }

        // Go through our subscription list. We shouldn't modify the list while we're iterating it.
        _depth++;
        try {
            // index loop, the list can't change under us but this is cheaper than an iterator per event
            for (int i = 0; i < subscribers.size(); i++) {
                Subscription<T> sub = (Subscription<T>) subscribers.get(i);
                if (sub.shouldDelete()) {
                    _toDelete.add(sub);
                    continue;
                }
                // one bad subscriber must not take the others (or whoever published) down with it
                try {
                    sub.accept(event);
                } catch (ClassCastException e) {
                    System.err.println("TRIED PUBLISHING MISMAPPED EVENT: " + event);
                    report(e);
                } catch (Throwable t) {
                    report(t);
                }
            }
        } finally {
            _depth--;
            if (_depth == 0) {
                applyPending();
            }
        }
    }

    // adds what was subscribed during a publish and drops what was unsubscribed. never while anything is iterating
    private static void applyPending() {
        if (!_toAdd.isEmpty()) {
            List<Pending> adding = new ArrayList<>(_toAdd);
            _toAdd.clear();
            for (Pending toAdd : adding) {
                subscribeInternal(toAdd.type(), toAdd.sub());
            }
        }
        if (!_toDelete.isEmpty()) {
            // a subscription is only deleted once, and it lives in one topic
            for (List<Subscription> list : _topics.values()) {
                list.removeAll(_toDelete);
            }
            _toDelete.clear();
        }
    }

    private static void report(Throwable t) {
        try {
            _errorHandler.accept(t);
        } catch (Throwable ignored) {
            // the handler is part of the cleanup, it does not get to throw out of a publish either
        }
    }

    private static <T> void subscribeInternal(Class<T> type, Subscription<T> sub) {
        if (!_topics.containsKey(type)) {
            _topics.put(type, new ArrayList<>());
        }
        _topics.get(type).add(sub);
    }

    public static <T> Subscription<T> subscribe(Class<T> type, Consumer<T> consumeEvent) {
        Subscription<T> sub = new Subscription<>(consumeEvent);
        if (_depth > 0) {
            _toAdd.add(new Pending(type, sub));
        } else {
            subscribeInternal(type, sub);
        }
        return sub;
    }

    public static <T> void unsubscribe(Subscription<T> subscription) {
        if (subscription != null)
            subscription.delete();
    }

    // altoclef gave up (or failed to start): nobody is listening to anything any more
    public static void clear() {
        _topics.clear();
        _toAdd.clear();
        _toDelete.clear();
    }

    public static void setErrorHandler(Consumer<Throwable> handler) {
        _errorHandler = handler;
    }

    // how many are subscribed to this type right now, deleted ones that haven't been swept yet not counted
    public static int subscriberCount(Class type) {
        List<Subscription> list = _topics.get(type);
        int count = 0;
        if (list != null) {
            for (Subscription sub : list) {
                if (!sub.shouldDelete()) {
                    count++;
                }
            }
        }
        for (Pending pending : _toAdd) {
            if (pending.type() == type && !pending.sub().shouldDelete()) {
                count++;
            }
        }
        return count;
    }
}
