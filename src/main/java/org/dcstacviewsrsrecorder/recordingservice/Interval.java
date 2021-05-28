package org.dcstacviewsrsrecorder.recordingservice;

public class Interval<T extends Comparable<T>> {
    private final T start;
    private final T end;

    public static <T extends Comparable<T>> Interval<T> between(T start, T end) {
        return new Interval<>(start, end);
    }

    private Interval(T start, T end) {
        if(start.compareTo(end) > 0) {
            throw new IllegalStateException("Start '" + start + "' is after end '" + end + "'");
        }
        this.start = start;
        this.end = end;
    }

    public T getStart() {
        return start;
    }

    public T getEnd() {
        return end;
    }

    /*
        x1 <= y2 && y1 <= x2
        https://stackoverflow.com/questions/3269434/whats-the-most-efficient-way-to-test-two-integer-ranges-for-overlap
     */
    public boolean intersects(Interval<T> other) {
        return start.compareTo(other.end) <= 0 && other.start.compareTo(end) <= 0;
    }

    @Override
    public String toString() {
        return "Interval{" +
                "start=" + start +
                ", end=" + end +
                '}';
    }
}
