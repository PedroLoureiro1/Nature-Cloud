public class Main {

    public static void main(String[] args) {
        int minInstances = 1;
        int maxInstances = 5;
        long checkerInterval = 30; // in seconds

        AutoScaler autoScaler = new AutoScaler(minInstances, maxInstances, checkerInterval);
        autoScaler.start();
    }
}
