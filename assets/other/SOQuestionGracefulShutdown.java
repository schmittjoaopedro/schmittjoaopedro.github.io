public class SOQuestionGracefulShutdown {

    public static void main(String[] args) throws InterruptedException {
        ScheduledThreadPoolExecutor taskExecutor = new ScheduledThreadPoolExecutor(2);

        AtomicLong counter = new AtomicLong(0);
        taskExecutor.scheduleWithFixedDelay(() -> {
            try {
                counter.incrementAndGet();
                System.out.println("Task " + counter.get() + " started");
                Thread.sleep(10_000);
                System.out.println("Task " + counter.get() + " finished");
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }, 0, 1, TimeUnit.SECONDS);


        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("Shutting down");
                taskExecutor.shutdown();
                taskExecutor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }));

        System.out.println("Started send a SIGTERM right after this message using: kill -15 " + ProcessHandle.current().pid());

        for (; ; ) {
            Thread.sleep(100);
        }
    }

    public static void mainWithTaskExecutor(String[] args) throws InterruptedException {

        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(2);
        taskExecutor.setMaxPoolSize(2);
        taskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        taskExecutor.initialize();

        for (int i = 0; i < 3; i++) {
            final int v = i;
            taskExecutor.execute(() -> {
                try {
                    Thread.sleep(10_000);
                    System.out.println("Task " + v + " completed");
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("Shutting down");
                taskExecutor.shutdown();
                taskExecutor.getThreadPoolExecutor().awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }));

        System.out.println("Jobs started, please send a SIGTERM right after this message using: kill -15 " + ProcessHandle.current().pid());

        for (; ; ) {
            Thread.sleep(100);
        }
    }

  
}
