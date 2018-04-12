package gr.aueb.dist.partOne.Models;

import gr.aueb.dist.partOne.Abstractions.IMaster;
import gr.aueb.dist.partOne.Server.CommunicationMessage;
import gr.aueb.dist.partOne.Server.MessageType;
import gr.aueb.dist.partOne.Server.Server;
import gr.aueb.dist.partOne.Utils.MatrixHelpers;
import gr.aueb.dist.partOne.Utils.ParserUtils;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.ops.transforms.Transforms;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class Master extends Server implements IMaster{
    private int currentIteration;
    private int howManyWorkersToWait;

    private double latestError;
    private long LoopCalculationStartTime;

    private ArrayList<Worker> availableWorkers;
    private ArrayList<CommunicationMessage> XMessages;
    private ArrayList<CommunicationMessage> YMessages;

    private HashMap<String, Double> XExecutionTimes;
    private HashMap<String, Double> YExecutionTimes;

    private INDArray RUpdated;
    private INDArray R, P, C ,X ,Y;

    private final static int MAX_ITERATIONS = 200;
    private final static double L = 0.1;
    private final static double A = 40;

    private final static String NEW_X_PATH = "data/newX.txt";
    private final static String NEW_Y_PATH = "data/newY.txt";

    public Master(){}

    /**
     * Runnable Implementation
     */
    public synchronized void run() {
        try{
            ObjectOutputStream out = new ObjectOutputStream(getSocketConn().getOutputStream());
            ObjectInputStream in = new ObjectInputStream(getSocketConn().getInputStream());

            CommunicationMessage message = (CommunicationMessage) in.readObject();

            switch(message.getType()){
                case HELLO_WORLD:{
                    Worker worker = new Worker();
                    worker.setId(message.getServerName());
                    worker.setIp(message.getIp());
                    worker.setPort(message.getPort());
                    worker.setInstanceCpuCores(message.getCpuCores());
                    worker.setInstanceRamSize(message.getRamGBSize());
                    availableWorkers.add(worker);

                    System.out.println(worker.toString());

                    if(availableWorkers.size() >= howManyWorkersToWait){
                        StartMatrixFactorization();
                    }

                    break;
                }
                case X_CALCULATED:{
                    XMessages.add(message);
                    XExecutionTimes.put(message.getServerName(), message.getExecutionTime());
                    if(XMessages.size() >= howManyWorkersToWait){
                        LinkedList<INDArray> XDist = new LinkedList<>();

                        //Ascending sort of fromUser
                        XMessages.sort(Comparator.comparingInt(CommunicationMessage::getFromUser));
                        XMessages.forEach((msg) -> XDist.add(msg.getXArray()));

                        X = Nd4j.vstack(XDist);

                        DistributeXMatrixToWorkers();

                        //When finished, clear it for the next loop
                        XMessages.clear();
                    }

                    break;
                }
                case Y_CALCULATED:{
                    YMessages.add(message);
                    YExecutionTimes.put(message.getServerName(), message.getExecutionTime());
                    if(YMessages.size() >= howManyWorkersToWait){
                        LinkedList<INDArray> YDist = new LinkedList<>();

                        //Ascending sort of fromUser
                        YMessages.sort(Comparator.comparingInt(CommunicationMessage::getFromUser));
                        YMessages.forEach((msg) -> YDist.add(msg.getYArray()));

                        Y = Nd4j.vstack(YDist);

                        double error = CalculateError();

                        // we want our error to be min in each iteration
                        if(latestError < error){
                            System.out.println("Previous Error: " + latestError);
                            System.out.println("Current Error: " + error);
                            System.out.println("False Iteration");
                        }

                        System.out.println("***********************************************");
                        System.out.println("Loop No.: " + currentIteration);
                        System.out.println("Error: " + error);
                        System.out.println("Loop Elapsed Time: " +
                                ParserUtils.GetTimeInSec(LoopCalculationStartTime) + "sec");
                        System.out.println("***********************************************");

                        latestError = error;
                        currentIteration++;

                        if(latestError < 0.001 || currentIteration >= MAX_ITERATIONS){
                            FinishMatrixFactorization();
                            return;
                        }

                        DistributeYMatrixToWorkers();
                        LoopCalculationStartTime = System.nanoTime();

                        //When finished, clear it for the next loop
                        YMessages.clear();
                    }

                    break;
                }
                default:{
                    break;
                }
            }
        }
        catch (ClassNotFoundException | IOException ignored) {}
    }

    private void StartMatrixFactorization(){
        int totalCores = availableWorkers
                .stream()
                .map(Worker::getInstanceCpuCores)
                .reduce(0, (a, b) -> a + b);

        System.out.println("Number Of Workers: " + howManyWorkersToWait + " Workers");
        System.out.println("Total Cores: " + totalCores + " Cores");

        // C and P matrices only need to be calculated once and passed once to the workers
        C = (R.mul(A)).add(1);
        P = Transforms.greaterThanOrEqual(R, Nd4j.zeros(R.rows(),R.columns()));

        // let's get the K << max{U,I}
        // meaning a number much smaller than the biggest column or dimension
        int BiggestDimension = R.columns() > R.rows() ?
                R.columns() : R.rows();

        int K = BiggestDimension / 10;

        X = MatrixHelpers.GenerateRandomMatrix(R, K, false);
        Y = MatrixHelpers.GenerateRandomMatrix(R, K, true);

        TransferMatricesToWorkers();
        DistributeYMatrixToWorkers();

        LoopCalculationStartTime = System.nanoTime();
    }

    private void FinishMatrixFactorization(){
        System.out.println("**************************************");
        System.out.println("Writing to " + NEW_X_PATH + ", " + NEW_Y_PATH + "newY.txt");
        Nd4j.writeTxt(X, NEW_X_PATH);
        Nd4j.writeTxt(Y, NEW_Y_PATH);

        long startTime = System.nanoTime();

        RUpdated = X.mmul(Y.transpose());

        System.out.println("New R Calculated in: " + ParserUtils.GetTimeInSec(startTime) + "sec");
        System.out.println("**************************************");
    }

    /**
     * IMaster Implementation
     */
    public void Initialize() {
        currentIteration = 0;
        latestError = Double.MAX_VALUE - 1;

        availableWorkers = new ArrayList<>();

        XMessages = new ArrayList<>();
        YMessages = new ArrayList<>();

        XExecutionTimes = new HashMap<>();
        YExecutionTimes = new HashMap<>();

        R = ParserUtils.LoadDataSet("data/inputMatrix.csv");
        if(R == null){
            System.out.println("Wrong DataSet! Please contact with the Developers!");
            return;
        }

        C = Nd4j.zeros(R.rows(), R.columns());
        P = Nd4j.zeros(R.rows(), R.columns());

        Path newXFile = Paths.get(NEW_X_PATH);
        Path newYFile = Paths.get(NEW_Y_PATH);
        if(Files.exists(newXFile) && !Files.exists(newYFile)){
            X = Nd4j.readTxt(NEW_X_PATH);
            Y = Nd4j.readTxt(NEW_Y_PATH);
            System.out.println("**************************************");
            System.out.println("Loading to " + NEW_X_PATH + ", " + NEW_Y_PATH + "newY.txt");
            System.out.println("**************************************");
        }else{
            System.out.println("No trained data found to load!. Waiting for master connections...");
        }

        this.OpenServer();
    }

    public void TransferMatricesToWorkers(){
        CommunicationMessage msg = new CommunicationMessage();
        msg.setType(MessageType.TRANSFER_MATRICES);
        msg.setCArray(C);
        msg.setPArray(P);
        msg.setXArray(X);
        msg.setYArray(Y);

        SendBroadcastMessageToWorkers(msg);
    }

    public void DistributeXMatrixToWorkers() {
        HashMap<String, Integer[]> workerIndexes = SplitMatrix(Y, "Y");

        availableWorkers.parallelStream().forEach(worker -> {
            CommunicationMessage xMessage = new CommunicationMessage();
            xMessage.setType(MessageType.CALCULATE_Y);
            xMessage.setXArray(X);
            xMessage.setFromUser(workerIndexes.get(worker.getId())[0]);
            xMessage.setToUser(workerIndexes.get(worker.getId())[1]);
            SendMessageToWorker(xMessage, worker);
        });
    }

    public void DistributeYMatrixToWorkers() {
        HashMap<String, Integer[]> workerIndexes = SplitMatrix(X, "X");

        availableWorkers.parallelStream().forEach(worker -> {
            CommunicationMessage xMessage = new CommunicationMessage();
            xMessage.setType(MessageType.CALCULATE_X);
            xMessage.setYArray(Y);
            xMessage.setFromUser(workerIndexes.get(worker.getId())[0]);
            xMessage.setToUser(workerIndexes.get(worker.getId())[1]);
            SendMessageToWorker(xMessage, worker);
        });
    }

    public void SendBroadcastMessageToWorkers(CommunicationMessage message) {
        availableWorkers.parallelStream().forEach(worker -> SendMessageToWorker(message, worker));
    }

    public void SendMessageToWorker(CommunicationMessage message, Worker worker) {
        ObjectInputStream in = null;
        ObjectOutputStream out = null;
        Socket socket = null;

        try{
            socket = new Socket(worker.getIp(), worker.getPort());

            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());

            out.writeObject(message);
            out.flush();
        }catch(IOException ignored){}
        finally {
            this.CloseConnections(socket, in, out);
        }
    }

    public double CalculateError() {
        INDArray temp = X.mmul(Y.transpose());
        temp.subi(P);
        temp.muli(temp);
        temp.muli(C);

        INDArray normX = Nd4j.sum(X.mul(X),0);
        INDArray normY = Nd4j.sum(Y.mul(Y), 0);
        INDArray norma = normX.add(normY);
        norma.muli(L);

        double sumPartOne = temp.sumNumber().doubleValue();
        double sumPartTwo = norma.sumNumber().doubleValue();

        return sumPartOne + sumPartTwo;
    }

    public double CalculateScore(int x, int y) {
        return 0;
    }

    public List<Poi> CalculateBestLocalPOIsForUser(int user, int numberOfResults) {
        List<Poi> recommendedPOIs = new LinkedList<>();

        INDArray pois = RUpdated.getRow(user);

        double previousMax = Double.MAX_VALUE;
        for (int poi = 0; poi < numberOfResults; poi++) {
            int currentMaxIndex = getMax(pois, previousMax, user);
            previousMax = pois.getDouble(1, currentMaxIndex);

            //TODO Create the poi based on the currentMaxIndex
            recommendedPOIs.add(new Poi());
        }

        return recommendedPOIs;
    }

    /**
     * Helper Methods
     */
    boolean first = true;
    HashMap<String, Integer> latestWorkersDistribution = new HashMap<>();
    public HashMap<String, Integer[]> SplitMatrix(INDArray matrix, String matrixName){
        int totalCores = availableWorkers
                .stream()
                .map(Worker::getInstanceCpuCores)
                .reduce(0, (a, b) -> a + b);

        int currentIndex = -1;

        int rowsPerCore = 0;
        LinkedHashMap<String, Double> sortedMap =
                new LinkedHashMap<>();


        if(first) {
            rowsPerCore = matrix.rows() / totalCores;
        }
        else{
            ArrayList<String> mapKeys = new ArrayList<>(XExecutionTimes.keySet());
            ArrayList<Double> mapValues = new ArrayList<>(XExecutionTimes.values());
            Collections.sort(mapValues);
            Collections.sort(mapKeys);

            Iterator<Double> valueIt = mapValues.iterator();
            while (valueIt.hasNext()) {
                Double val = valueIt.next();
                Iterator<String> keyIt = mapKeys.iterator();

                while (keyIt.hasNext()) {
                    String key = keyIt.next();
                    Double comp1 = XExecutionTimes.get(key);
                    Double comp2 = val;

                    if (comp1.equals(comp2)) {
                        keyIt.remove();
                        sortedMap.put(key, val);
                        break;
                    }
                }
            }
        }

        HashMap<String, Integer[]> workerIndexes = new HashMap<>();

        double totalExTime = XExecutionTimes.values()
                .stream()
                .reduce(0.0, (a, b) -> a + b);

        double meanExTime = totalExTime / howManyWorkersToWait;

        Iterator<Double> valueIt = sortedMap.values().iterator();
        Double lastValue = 0.0;
        while (valueIt.hasNext()) {
            lastValue = valueIt.next();
        }
        boolean needToRedistribute = false;
        if(lastValue - meanExTime > 1.0) {
            needToRedistribute = true;
        }

        System.out.println("***********************************************");

        if(first || !needToRedistribute) {

            for (int i = 0; i < availableWorkers.size(); i++) {
                Worker worker = availableWorkers.get(i);

                int workerRows = rowsPerCore * worker.getInstanceCpuCores();

                Integer[] indexes = new Integer[2];
                indexes[0] = currentIndex + 1;

                if (i == availableWorkers.size() - 1) {
                    if (currentIndex != matrix.rows()) {
                        indexes[1] = matrix.rows() - 1;
                        System.out.println("Distributing " + matrixName +
                                " to " + worker.getId() +
                                " from " + indexes[0] +
                                " to " + indexes[1] +
                                ". Total: " + (indexes[1] - indexes[0] + 1));
                    }
                } else {
                    currentIndex += workerRows;
                    indexes[1] = currentIndex;
                    System.out.println("Distributing " + matrixName +
                            " to " + worker.getId() +
                            " from " + indexes[0] +
                            " to " + indexes[1] +
                            ". Total: " + (indexes[1] - indexes[0] + 1));
                }
                latestWorkersDistribution.put(worker.getId(), indexes[1] - indexes[0] + 1);
                workerIndexes.put(worker.getId(), indexes);
            }
        }
        else {
            Iterator<String> keyIt = sortedMap.keySet().iterator();
            String lastKey = "";
            while (keyIt.hasNext()) {
                lastKey = keyIt.next();
            }
            int slowestWorkerIndices = latestWorkersDistribution.get(lastKey);
            int percentMoved = slowestWorkerIndices / 10;
            latestWorkersDistribution.put(lastKey, slowestWorkerIndices - percentMoved);
            int rowsToBeAddedToRest = percentMoved / (howManyWorkersToWait - 1);

            Iterator<String> keyIt2 = sortedMap.keySet().iterator();
            while (keyIt2.hasNext()) {
                String currentKey = keyIt2.next();
                if(currentKey.equals(lastKey)) break;
                int currentIndices = latestWorkersDistribution.get(currentKey);
                latestWorkersDistribution.put(currentKey, currentIndices + rowsToBeAddedToRest);
                percentMoved -= rowsToBeAddedToRest;
            }
            if(percentMoved > 0) {
                Iterator<String> keyIt3 = sortedMap.keySet().iterator();
                if (keyIt3.hasNext()) {
                    String currentKey = keyIt3.next();
                    int currentIndices = latestWorkersDistribution.get(currentKey);
                    latestWorkersDistribution.put(currentKey, currentIndices + percentMoved);
                }
            }

            for (int i = 0; i < availableWorkers.size(); i++) {
                Worker worker = availableWorkers.get(i);

                int workerRows = latestWorkersDistribution.get(worker.getId());

                Integer[] indexes = new Integer[2];
                indexes[0] = currentIndex + 1;

                if (i == availableWorkers.size() - 1) {
                    if (currentIndex != matrix.rows()) {
                        indexes[1] = matrix.rows() - 1;
                        System.out.println("Distributing " + matrixName +
                                " to " + worker.getId() +
                                " from " + indexes[0] +
                                " to " + indexes[1] +
                                ". Total: " + (indexes[1] - indexes[0] + 1));
                    }
                } else {
                    currentIndex += workerRows;
                    indexes[1] = currentIndex;
                    System.out.println("Distributing " + matrixName +
                            " to " + worker.getId() +
                            " from " + indexes[0] +
                            " to " + indexes[1] +
                            ". Total: " + (indexes[1] - indexes[0] + 1));
                }
                latestWorkersDistribution.put(worker.getId(), indexes[1] - indexes[0] + 1);
                workerIndexes.put(worker.getId(), indexes);
            }
        }

        System.out.println("***********************************************");

        first = false;

        return workerIndexes;
    }

    private int getMax(INDArray pois, double previousMax, int user){
        double max = -1;
        int maxIndex = -1;

        for (int i = 0; i < pois.columns(); i++) {
            double element = pois.getDouble(1, i);
            if (previousMax > element &&
                    element > max &&
                    P.getDouble(user, i) != 1){
                max = element;
                maxIndex = i;
            }
        }
        return maxIndex;
    }

    /**
     *   Getters and Setters
     */
    public int getHowManyWorkersToWait() {
        return howManyWorkersToWait;
    }

    public void setHowManyWorkersToWait(int howManyWorkersToWait) {
        this.howManyWorkersToWait = howManyWorkersToWait;
    }
}
