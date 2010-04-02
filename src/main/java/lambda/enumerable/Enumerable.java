package lambda.enumerable;

import static java.lang.Boolean.*;
import static java.util.Arrays.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import lambda.Fn1;
import lambda.Fn2;

/**
 * Ruby/Smalltalk style internal iterators for Java 5 using bytecode
 * transformation to capture expressions as closures.
 * 
 * {@link http://ruby-doc.org/core/classes/Enumerable.html#M003127}
 */
public class Enumerable {
    /**
     * Calls block for each item in collection.
     */
    public static <E, R> R each(Iterable<E> col, Fn1<E, R> block) {
        R result = null;
        for (E each : col)
            result = block.call(each);
        return result;
    }

    /**
     * Calls block with two arguments, the item and its index, for each item in
     * collection.
     */
    public static <E, R> R eachWithIndex(Iterable<E> col, Fn2<E, Integer, R> block) {
        int i = 0;
        R result = null;
        for (E each : col)
            result = block.call(each, i++);
        return result;
    }

    /**
     * Returns a new array with the results of running block once for every
     * element in collection.
     */
    public static <E, R> List<R> collect(Iterable<E> col, Fn1<E, R> block) {
        List<R> result = new ArrayList<R>();
        for (E each : col)
            result.add(block.call(each));
        return result;
    }

    /**
     * @see #select()
     */
    public static <E> List<E> findAll(Iterable<E> col, Fn1<E, Boolean> block) {
        return select(col, block);
    }

    /**
     * Returns an array containing all elements of collection for which block is
     * not false.
     */
    public static <E> List<E> select(Iterable<E> col, Fn1<E, Boolean> block) {
        List<E> result = new ArrayList<E>();
        for (E each : col)
            if (block.call(each))
                result.add(each);
        return result;
    }

    /**
     * Returns an array containing all elements of collection for which block is
     * false.
     */
    public static <E> List<E> reject(Iterable<E> col, Fn1<E, Boolean> block) {
        List<E> result = new ArrayList<E>();
        for (E each : col)
            if (!block.call(each))
                result.add(each);
        return result;
    }

    /**
     * Returns a list containing the items in collection sorted, according to
     * their own compareTo method.
     */
    public static <E extends Object & Comparable<? super E>> List<E> sort(Iterable<E> col) {
        List<E> result = new ArrayList<E>();
        for (E each : col)
            result.add(each);
        Collections.sort(result);
        return result;
    }

    /**
     * Returns a list containing the items in collection sorted by using the
     * results of the supplied block.
     */
    @SuppressWarnings("unchecked")
    public static <E> List<E> sort(Iterable<E> col, final Fn2<E, E, Integer> block) {
        List<E> result = new ArrayList<E>();
        for (E each : col)
            result.add(each);
        Collections.sort(result, block.as(Comparator.class));
        return result;
    }

    /**
     * Sorts collection using a set of keys generated by mapping the values in
     * collection through the given block.
     */
    public static <E, R extends Object & Comparable<? super R>> List<E> sortBy(Iterable<E> col, final Fn1<E, R> block) {
        List<E> result = new ArrayList<E>();
        for (E each : col)
            result.add(each);
        Collections.sort(result, new Comparator<E>() {
            public int compare(E o1, E o2) {
                return block.call(o1).compareTo(block.call(o2));
            }
        });
        return result;
    }

    /**
     * Returns two lists, the first containing the elements of collection for
     * which the block evaluates to true, the second containing the rest.
     */
    @SuppressWarnings("unchecked")
    public static <E> List<List<E>> partition(Iterable<E> col, Fn1<E, Boolean> block) {
        List<E> result1 = new ArrayList<E>();
        List<E> result2 = new ArrayList<E>();
        for (E each : col)
            if (block.call(each))
                result1.add(each);
            else
                result2.add(each);
        return new ArrayList<List<E>>(asList(result1, result2));
    }

    /**
     * @see #detect()
     */
    public static <E> E find(Iterable<E> col, Fn1<E, Boolean> block) {
        return detect(col, block);
    }

    /**
     * Passes each entry in collection to block. Returns the first for which
     * block is not false. If no object matches, it returns null.
     */
    public static <E> E detect(Iterable<E> col, Fn1<E, Boolean> block) {
        return detect(col, block, null);
    }

    /**
     * @see #detect(Iterable, Fn1, Object)
     */
    public static <E> E find(Iterable<E> col, Fn1<E, Boolean> block, E ifNone) {
        return detect(col, block, ifNone);
    }

    /**
     * Passes each entry in collection to block. Returns the first for which
     * block is not false. If no object matches, it returns ifNone.
     */
    public static <E> E detect(Iterable<E> col, Fn1<E, Boolean> block, E ifNone) {
        for (E each : col)
            if (block.call(each))
                return each;
        return ifNone;
    }

    /**
     * Passes each element of the collection to the given block. The method
     * returns true if the block ever returns a value other than false or null.
     */
    public static <E> boolean any(Iterable<E> col, Fn1<E, ?> block) {
        for (E each : col) {
            Object result = block.call(each);
            if (result != FALSE && result != null)
                return true;
        }
        return false;
    }

    /**
     * Passes each element of the collection to the given block. The method
     * returns true if the block never returns false or null.
     */
    public static <E> boolean all(Iterable<E> col, Fn1<E, ?> block) {
        for (E each : col) {
            Object result = block.call(each);
            if (result == FALSE || result == null)
                return false;
        }
        return true;
    }

    /**
     * Combines the elements of collection by applying the block to an
     * accumulator value (memo) and each element in turn. At each step, memo is
     * set to the value returned by the block. This form lets you supply an
     * initial value for memo.
     */
    public static <E, R> R inject(Iterable<E> col, R initial, Fn2<R, E, R> block) {
        for (E each : col)
            initial = block.call(initial, each);
        return initial;
    }

    /**
     * Combines the elements of collection by applying the block to an
     * accumulator value (memo) and each element in turn. At each step, memo is
     * set to the value returned by the block. This form uses the first element
     * of the collection as a the initial value (and skips that element while
     * iterating).
     */
    public static <E> E inject(Iterable<E> col, Fn2<E, E, E> block) {
        Iterator<E> i = col.iterator();
        E initial = i.next();
        while (i.hasNext())
            initial = block.call(initial, i.next());
        return initial;
    }

    /**
     * Named parameter for detect.
     * 
     * @see #detect(Iterable, Fn1, Object)
     * @see #find(Iterable, Fn1, Object)
     */
    public static <R> R ifNone(R defaultValue) {
        return defaultValue;
    }
}
