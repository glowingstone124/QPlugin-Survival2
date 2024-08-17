package vip.qoriginal.quantumplugin.metro;

public class newMinecart {
    double calcXorZ(long tick, double momentum) {
        if (tick <= 0) {
            throw new IllegalArgumentException("tick must be greater than 0");
        }
        double result = 0;
        // t and momentum must be greater than 0
        // when minecart moves, vector "R" can be considered as movement in X or Z.
        for (int n = 0; n < tick; n++) {
            result += momentum * Math.pow(0.98, n);
        }
        return result;
    }

    double calcY(long tick, double momentum) {
        if (tick <= 0) {
            throw new IllegalArgumentException("tick must be greater than 0");
        }
        double result = 0;
        // t and momentum must be greater than 0
        for (int n = 0; n < tick; n++) {
            result += -2 + Math.pow(0.98, n) * (momentum + 1.96);
        }
        return result;
    }

    Point[] calcMinecartPos(double momXorZ, double momY, long tickLimit, double offsetXorZ, double offsetY) {
        //ticklimit must be greater than 0!!!
        if (tickLimit <= 0) {
            throw new IllegalArgumentException("tickLimit must be greater than 0");
        }
        Point[] positions = new Point[(int) tickLimit];
        for (int tick = 0; tick < tickLimit; tick++) {
            double xOrZ = calcXorZ(tick + 1, momXorZ) + offsetXorZ;
            double y = calcY(tick + 1, momY) + offsetY;
            positions[tick] = new Point(xOrZ, y);
        }
        return positions;
    }

    public static class Point {
        double x = 0;
        double y = 0;

        Point(double x, double y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public String toString() {
            return "Point{" +
                    "x=" + x +
                    ", y=" + y +
                    '}';
        }
    }
}
