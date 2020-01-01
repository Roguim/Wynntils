/*
 *  * Copyright © Wynntils - 2018 - 2020.
 */

package com.wynntils.core.utils.objects;

import com.wynntils.core.utils.objects.Functions.Cubic;

import javax.vecmath.Point3d;
import javax.vecmath.Tuple3d;
import javax.vecmath.Vector3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleFunction;
import java.util.function.ToDoubleFunction;

public final class CubicSplines {

    public static Cubic[] calculate1DSpline(List<Double> points) {
        return calculate1DSpline(points, c -> c);
    }

    public static <T> Cubic[] calculate1DSpline(List<T> points, ToDoubleFunction<T> getDimension) {
        if (points.isEmpty()) {
            return new Cubic[0];
        } else if (points.size() == 1) {
            return new Cubic[]{
                new Cubic(getDimension.applyAsDouble(points.get(0)), 0, 0, 0)  // Constant cubic for the single point
            };
        }

        /*

        Need to solve the tridiagonal system of

        [ 2 1           ] [ D_0 ]   [ 3(y_1   - y_0  ) ]
        [ 1 4 1         ] [ D_1 ]   [ 3(y_2   - y_0  ) ]
        [   1 4 1       ] [ D_2 ]   [ 3(y_3   - y_1  ) ]
        [ ............. ] [ ... ] = [ ................ ]
        [       1 4 1   ] [D_n-2]   [ 3(y_n-1 - y_n-3) ]
        [         1 4 1 ] [D_n-1]   [ 3(y_n   - y_n-2) ]
        [           1 2 ] [ D_n ]   [ 3(y_n   - y_n-1) ]

        So convert it into a right triangular matrix, by subtracting a quarter of
        the row above to get rid of the 1 on the left (And only half for the top and
        bottom row) and then use back substitution to solve (D_n will be in terms of 1 unknown)

         */

        int chords = points.size() - 1;

        double[] gamma = new double[chords + 1];
        double[] delta = new double[chords + 1];
        double[] D = new double[chords + 1];

        gamma[0] = 0.5;
        for (int i = 1; i < chords; i++) {
            gamma[i] = 1 / (4 - gamma[i - 1]);
        }
        gamma[chords] = 1 / (2. - gamma[chords - 1]);

        double p0 = getDimension.applyAsDouble(points.get(0));
        double p1 = getDimension.applyAsDouble(points.get(1));

        delta[0] = 3 * (p1 - p0) * gamma[0];
        for (int i = 1; i < chords; i++) {
            p0 = getDimension.applyAsDouble(points.get(i - 1));
            p1 = getDimension.applyAsDouble(points.get(i + 1));
            delta[i] = (3 * (p1 - p0) - delta[i - 1]) * gamma[i];
        }
        p0 = getDimension.applyAsDouble(points.get(chords - 1));
        p1 = getDimension.applyAsDouble(points.get(chords));

        delta[chords] = (3 * (p1 - p0) - delta[chords - 1]) * gamma[chords];

        D[chords] = delta[chords];
        for (int i = chords - 1; i >= 0; i--) {
            D[i] = delta[i] - gamma[i] * D[i + 1];
        }

        Cubic[] cubics = new Cubic[chords];

        for (int i = 0; i < chords; i++) {
            p0 = getDimension.applyAsDouble(points.get(i));
            p1 = getDimension.applyAsDouble(points.get(i + 1));

            cubics[i] = new Cubic(
                p0,
                D[i],
                3 * (p1 - p0) - 2 * D[i] - D[i + 1],
                2 * (p0 - p1) +     D[i] + D[i + 1]
            );
        }

        return cubics;
    }

    public final static class Spline3D implements DoubleFunction<Location> {

        private ArrayList<Location> points;
        private transient Cubic[] xCubics;
        private transient Cubic[] yCubics;
        private transient Cubic[] zCubics;
        private transient boolean dirty;

        public Spline3D() {
            this(Collections.emptyList());
        }

        public Spline3D(Collection<? extends Point3d> points) {
            setPoints(points);
        }

        public void setPoints(Collection<? extends Point3d> points) {
            dirty = true;
            if (points == null) {
                this.points = new ArrayList<>();
                return;
            }
            this.points = new ArrayList<>(points.size());
            for (Point3d point : points) {
                this.points.add(new Location(point));
            }
        }

        public void addPoint(Point3d point) {
            dirty = true;
            points.add(new Location(point));
        }

        public void addPoints(Collection<? extends Point3d> newPoints) {
            if (newPoints == null || newPoints.isEmpty()) return;
            dirty = true;
            points.ensureCapacity(points.size() + newPoints.size());
            for (Point3d point : newPoints) {
                points.add(new Location(point));
            }
        }

        public void addPoints(int index, Collection<? extends Point3d> newPoints) {
            if (newPoints == null || newPoints.isEmpty()) return;
            dirty = true;
            ArrayList<Location> copy = new ArrayList<>(newPoints.size());
            for (Point3d point : newPoints) {
                copy.add(new Location(point));
            }
            points.addAll(index, copy);
        }

        public List<Location> getPoints() {
            return Collections.unmodifiableList(points);
        }

        private void recalculateAllCubics() {
            xCubics = calculate1DSpline(points, Tuple3d::getX);
            yCubics = calculate1DSpline(points, Tuple3d::getY);
            zCubics = calculate1DSpline(points, Tuple3d::getZ);

            dirty = false;
        }

        /**
         * Distance between two adjacent points (So length of chord that apply(index, t) is smoothing)
         *
         * @param index The point index
         * @return the distance between points[index] and points[index + 1]
         */
        public double distanceAt(int index) {
            return points.get(index).distance(points.get(index + 1));
        }

        /**
         * @param t Between 0 and 1
         * @return The location corresponding to that point on the spline
         */
        @Override
        public Location apply(double t) {
            t *= points.size();
            return apply((int) Math.floor(t), t % 1);
        }

        /**
         * The cubic interpolation between point[index] and point[index + 1]
         *
         * @param index Between 0 and points.size() - 1
         * @param t Between 0 and 1
         * @return
         */
        public Location apply(int index, double t) {
            if (dirty) recalculateAllCubics();

            return applyUnchecked(index, t);
        }

        private Location applyUnchecked(int index, double t) {
            return new Location(xCubics[index].applyAsDouble(t), yCubics[index].applyAsDouble(t), zCubics[index].applyAsDouble(t));
        }

        public Vector3d derivative(double t) {
            t *= points.size();
            return derivative((int) Math.floor(t), t % 1);
        }

        public Vector3d derivative(int index, double t) {
            if (dirty) recalculateAllCubics();

            return derivativeUnchecked(index, t);
        }

        private Vector3d derivativeUnchecked(int index, double t) {
            return new Vector3d(xCubics[index].derivative().applyAsDouble(t), yCubics[index].derivative().applyAsDouble(t), zCubics[index].derivative().applyAsDouble(t));
        }

        private static final double DEFAULT_SAMPLE_RATE = 10;  // How many samples per block

        public List<Location> sample() {
            return sample(DEFAULT_SAMPLE_RATE);
        }

        /**
         * Sample this function in such a way that the function is evaluated at every point
         * and there are `sampleRate` samples per block between each point
         *
         * @param sampleRate The resolution of the sample (per block/metre)
         * @return A list of values of this function sampled at monotonically increasing values
         */
        public List<Location> sample(double sampleRate) {
            if (points.isEmpty()) {
                return Collections.emptyList();
            } else if (points.size() == 1) {
                return Collections.singletonList(points.get(0));
            }

            if (dirty) recalculateAllCubics();

            ArrayList<Location> result = new ArrayList<>();
            for (int i = 0; i < points.size() - 1; ++i) {
                double chordLength = distanceAt(i);
                double oneBlock = 1 / chordLength;
                double samplePeriod = oneBlock / sampleRate;
                result.add(new Location(points.get(i)));
                double currentSample = samplePeriod;
                while (currentSample < 0.999) {
                    result.add(applyUnchecked(i, currentSample));
                    currentSample += samplePeriod;
                }
            }
            result.add(new Location(points.get(points.size() - 1)));

            return result;
        }

        public List<Vector3d> sampleDerivative() {
            return sampleDerivative(DEFAULT_SAMPLE_RATE);
        }

        public List<Vector3d> sampleDerivative(double sampleRate) {
            if (points.size() <= 1) {
                return Collections.emptyList();
            }

            if (dirty) recalculateAllCubics();

            ArrayList<Vector3d> result = new ArrayList<>();
            for (int i = 0; i < points.size() - 1; ++i) {
                double chordLength = distanceAt(i);
                double oneBlock = 1 / chordLength;
                double samplePeriod = oneBlock / sampleRate;
                result.add(derivativeUnchecked(i, 0));
                double currentSample = samplePeriod;
                while (currentSample < 0.999) {
                    result.add(derivativeUnchecked(i, currentSample));
                    currentSample += samplePeriod;
                }
            }
            result.add(derivativeUnchecked(points.size() - 2, 1));

            return result;
        }

    }

}