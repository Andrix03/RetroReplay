package io.github.andrix03.retroreplay.pathing;

/**
 * Catmull-Rom spline through keyframe values, the same math as ReplayStudio's
 * CatmullRomSplineInterpolator (Replay Mod's default, alpha 0.5): one cubic Hermite polynomial
 * per segment with tangents alpha * (next - previous), the end tangents using the end values.
 * Values with a bound (angles, 360) are unwrapped first so the camera turns the short way.
 */
public final class CatmullRom {

	private final long[] times;
	/** Per segment: a, b, c, d of a*t^3 + b*t^2 + c*t + d over t in [0, 1]. */
	private final double[][] polynomials;

	/** times strictly increasing, at least two keyframes; bound NaN for unbounded values. */
	public CatmullRom(long[] times, double[] values, double alpha, double bound) {
		if (times.length != values.length || times.length < 2) {
			throw new IllegalArgumentException("Need at least two keyframes");
		}
		this.times = times.clone();
		double[] v = Double.isNaN(bound) ? values.clone() : unwrap(values, bound);
		polynomials = new double[v.length - 1][];
		for (int i = 0; i < v.length - 1; i++) {
			double p1 = v[i];
			double p2 = v[i + 1];
			double p0 = i > 0 ? v[i - 1] : p1;
			double p3 = i < v.length - 2 ? v[i + 2] : p2;
			double t0 = alpha * (p2 - p0);
			double t1 = alpha * (p3 - p1);
			polynomials[i] = new double[] { 2 * p1 - 2 * p2 + t0 + t1, -3 * p1 + 3 * p2 - 2 * t0 - t1, t0, p1 };
		}
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
