package io.github.andrix03.retroreplay.pathing;

/**
 * A curve through keyframe values as one cubic polynomial per segment (t from 0 to 1 between two
 * keyframes), like ReplayStudio's interpolators:
 * - Catmull-Rom (Replay Mod's default, alpha 0.5): Hermite segments with tangents
 *   alpha * (next - previous), the end tangents using the end values;
 * - cubic: a natural cubic spline (second derivative continuous, zero at both ends);
 * - linear.
 * Values with a bound (angles, 360) are unwrapped first so the camera turns the short way.
 */
public final class Spline {

	private final long[] times;
	/** Per segment: a, b, c, d of a*t^3 + b*t^2 + c*t + d over t in [0, 1]. */
	private final double[][] polynomials;

	private Spline(long[] times, double[][] polynomials) {
		this.times = times.clone();
		this.polynomials = polynomials;
	}

	private static double[] prepare(long[] times, double[] values, double bound) {
		if (times.length != values.length || times.length < 2) {
			throw new IllegalArgumentException("Need at least two keyframes");
		}
		return Double.isNaN(bound) ? values.clone() : unwrap(values, bound);
	}

	/** times strictly increasing, at least two keyframes; bound NaN for unbounded values. */
	public static Spline catmullRom(long[] times, double[] values, double alpha, double bound) {
		double[] v = prepare(times, values, bound);
		double[][] polynomials = new double[v.length - 1][];
		for (int i = 0; i < v.length - 1; i++) {
			double p1 = v[i];
			double p2 = v[i + 1];
			double p0 = i > 0 ? v[i - 1] : p1;
			double p3 = i < v.length - 2 ? v[i + 2] : p2;
			double t0 = alpha * (p2 - p0);
			double t1 = alpha * (p3 - p1);
			polynomials[i] = new double[] { 2 * p1 - 2 * p2 + t0 + t1, -3 * p1 + 3 * p2 - 2 * t0 - t1, t0, p1 };
		}
		return new Spline(times, polynomials);
	}

	/** Natural cubic spline: M[i-1] + 4 M[i] + M[i+1] = 6 (p[i+1] - 2 p[i] + p[i-1]), M at the ends 0. */
	public static Spline cubic(long[] times, double[] values, double bound) {
		double[] p = prepare(times, values, bound);
		int n = p.length - 1;
		double[] m = new double[n + 1];
		if (n >= 2) {
			// Thomas algorithm on the inner points
			double[] c = new double[n];
			double[] d = new double[n];
			for (int i = 1; i < n; i++) {
				double rhs = 6 * (p[i + 1] - 2 * p[i] + p[i - 1]);
				double denominator = 4 - (i > 1 ? c[i - 1] : 0);
				c[i] = 1 / denominator;
				d[i] = (rhs - (i > 1 ? d[i - 1] : 0)) / denominator;
			}
			for (int i = n - 1; i >= 1; i--) {
				m[i] = d[i] - c[i] * m[i + 1];
			}
		}
		double[][] polynomials = new double[n][];
		for (int i = 0; i < n; i++) {
			double m1 = m[i];
			double m2 = m[i + 1];
			polynomials[i] = new double[] { (m2 - m1) / 6, m1 / 2, p[i + 1] - p[i] - m1 / 3 - m2 / 6, p[i] };
		}
		return new Spline(times, polynomials);
	}

	public static Spline linear(long[] times, double[] values, double bound) {
		double[] p = prepare(times, values, bound);
		double[][] polynomials = new double[p.length - 1][];
		for (int i = 0; i < p.length - 1; i++) {
			polynomials[i] = new double[] { 0, 0, p[i + 1] - p[i], p[i] };
		}
		return new Spline(times, polynomials);
	}

	/** The value at time; before the first or after the last keyframe it holds the end value. */
	public double valueAt(long time) {
		if (time <= times[0]) {
			return polynomials[0][3];
		}
		int last = times.length - 1;
		if (time >= times[last]) {
			double[] p = polynomials[last - 1];
			return p[0] + p[1] + p[2] + p[3];
		}
		int segment = 0;
		while (times[segment + 1] < time) {
			segment++;
		}
		double t = (time - times[segment]) / (double) (times[segment + 1] - times[segment]);
		double[] p = polynomials[segment];
		return ((p[0] * t + p[1]) * t + p[2]) * t + p[3];
	}

	/** Like ReplayStudio: each value moved by whole bounds so no step is longer than half a bound. */
	static double[] unwrap(double[] values, double bound) {
		double[] result = new double[values.length];
		double half = bound / 2;
		Double last = null;
		int offset = 0;
		for (int i = 0; i < values.length; i++) {
			double value = mod(values[i], bound);
			if (last == null) {
				last = value;
				offset = (int) Math.floor(values[i] / bound);
			}
			if (Math.abs(value - last) > half) {
				offset += last < half ? -1 : 1;
			}
			result[i] = value + offset * bound;
			last = value;
		}
		return result;
	}

	private static double mod(double value, double m) {
		return value - Math.floor(value / m) * m;
	}
}
