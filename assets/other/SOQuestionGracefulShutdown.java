public class SOQuestionGracefulShutdown {

    public static void main(String[] args) throws InterruptedException {

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

        System.out.println("Started send a SIGTERM right after this message using: kill -15 " + ProcessHandle.current().pid());

        for (; ; ) {
            Thread.sleep(100);
        }
    }

  
}
