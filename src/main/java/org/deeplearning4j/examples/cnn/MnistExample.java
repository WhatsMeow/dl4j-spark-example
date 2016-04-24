package org.deeplearning4j.examples.cnn;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.serializers.FieldSerializer;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.util.ToolRunner;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.serializer.KryoRegistrator;
import org.apache.spark.storage.StorageLevel;
import org.deeplearning4j.datasets.iterator.impl.MnistDataSetIterator;
import org.deeplearning4j.eval.Evaluation;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.layers.ConvolutionLayer;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.conf.layers.SubsamplingLayer;
import org.deeplearning4j.nn.conf.layers.setup.ConvolutionLayerSetup;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.spark.impl.multilayer.SparkDl4jMultiLayer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.HdfsWriter;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Simple example of learning MNIST with spark (local)
 * NOTE: This example runs and gives reasonable results, but better performance could be obtained
 * with some additional tuning of network hyperparameters
 *
 * @author Alex Black
 */
public class MnistExample {
    private static final Logger log = LoggerFactory.getLogger(MnistExample.class);

    public static class AvgCount implements java.io.Serializable {
        public AvgCount() {
            total_ = 0;
            num_ = 0;
        }
        public AvgCount(int total, int num) {
            total_ = total;
            num_ = num;
        }
        public float avg() {
            return total_ / (float) num_;
        }
        public int total_;
        public int num_;
    }

    public static class AvgRegistrator implements KryoRegistrator {
        public void registerClasses(Kryo kryo) {
            kryo.register(AvgCount.class, new FieldSerializer(kryo, AvgCount.class));
        }
    }

    public static void main(String[] args) throws Exception {

        //Create spark context
        int nCores = 4; //Number of CPU cores to use for training
        SparkConf sparkConf = new SparkConf();
        sparkConf.setMaster("local[" + nCores + "]");
        sparkConf.setAppName("MNIST");
        sparkConf.set(SparkDl4jMultiLayer.AVERAGE_EACH_ITERATION, String.valueOf(true));
        sparkConf.set("spark.kryo.registrator", AvgRegistrator.class.getName());
        JavaSparkContext sc = new JavaSparkContext(sparkConf);


        int nChannels = 1;
        int outputNum = 10;
        int numSamples = 60000;
        int nTrain = 50000;
        int nTest = 10000;
        int batchSize = 64;
        int iterations = 1;
        int seed = 123;
        int nEpochs = 5;

        //Load data into memory
        log.info("Load data....");
        DataSetIterator mnistIter = new MnistDataSetIterator(1, numSamples, true);
        List<DataSet> allData = new ArrayList<>(numSamples);
        while (mnistIter.hasNext()) {
            allData.add(mnistIter.next());
        }
        Collections.shuffle(allData, new Random(12345));

        Iterator<DataSet> iter = allData.iterator();
        List<DataSet> train = new ArrayList<>(nTrain);
        List<DataSet> test = new ArrayList<>(nTest);

        int c = 0;
        while (iter.hasNext()) {
            if (c++ <= nTrain) train.add(iter.next());
            else test.add(iter.next());
        }

        JavaRDD<DataSet> sparkDataTrain = sc.parallelize(train);
        sparkDataTrain.persist(StorageLevel.MEMORY_ONLY());

        //Set up network configuration
        log.info("Build model....");
        MultiLayerConfiguration.Builder builder = new NeuralNetConfiguration.Builder()
                .seed(seed)
                .iterations(iterations)
                .regularization(true).l2(0.0005)
                .learningRate(0.01)//.biasLearningRate(0.02)
                //.learningRateDecayPolicy(LearningRatePolicy.Inverse).lrPolicyDecayRate(0.001).lrPolicyPower(0.75)
                .weightInit(WeightInit.XAVIER)
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                .updater(Updater.NESTEROVS).momentum(0.9)
                .list(6)
                .layer(0, new ConvolutionLayer.Builder(5, 5)
                        .nIn(nChannels)
                        .stride(1, 1)
                        .nOut(20)
                        .activation("identity")
                        .build())
                .layer(1, new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX)
                        .kernelSize(2,2)
                        .stride(2,2)
                        .build())
                .layer(2, new ConvolutionLayer.Builder(5, 5)
                        .nIn(nChannels)
                        .stride(1, 1)
                        .nOut(50)
                        .activation("identity")
                        .build())
                .layer(3, new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX)
                        .kernelSize(2,2)
                        .stride(2,2)
                        .build())
                .layer(4, new DenseLayer.Builder().activation("relu")
                        .nOut(500).build())
                .layer(5, new OutputLayer.Builder(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
                        .nOut(outputNum)
                        .activation("softmax")
                        .build())
                .backprop(true).pretrain(false);
        new ConvolutionLayerSetup(builder, 28, 28, 1);

        MultiLayerConfiguration conf = builder.build();
        MultiLayerNetwork net = new MultiLayerNetwork(conf);
        net.init();
        net.setUpdater(null);   //Workaround for minor bug in 0.4-rc3.8

        //Create Spark multi layer network from configuration
        SparkDl4jMultiLayer sparkNetwork = new SparkDl4jMultiLayer(sc, net);

        //Train network
        log.info("--- Starting network training ---");
        for (int i = 0; i < nEpochs; i++) {
            //Run learning. Here, we are training with approximately 'batchSize' examples on each executor
            net = sparkNetwork.fitDataSet(sparkDataTrain, nCores * batchSize);
            System.out.println("----- Epoch " + i + " complete -----");
        }

        //Evaluate (locally)
        Evaluation eval = new Evaluation();
        for (DataSet ds : test) {
            INDArray output = net.output(ds.getFeatureMatrix());
            eval.eval(ds.getLabels(), output);
        }
        log.warn(eval.stats());
        log.info("****************Example finished********************");

        log.info("Sve configure file to hdfs");
        //Write the network parameters:
        try (DataOutputStream dos = new DataOutputStream(Files.newOutputStream(Paths.get("model/coefficients.bin")))) {
            Nd4j.write(net.params(), dos);
        }

        //Write the network configuration:
        FileUtils.write(new File("model/conf.json"), net.getLayerWiseConfigurations().toJson());

        //Save the updater:
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream("model/updater.bin"))) {
            oos.writeObject(net.getUpdater());
        }
//        ToolRunner.run(new HdfsWriter(), new String[]{"model/conf.json", "/user/hduser/mnist_model/conf.json"});
//        ToolRunner.run(new HdfsWriter(), new String[]{"model/updater.bin", "/user/hduser/mnist_model/updater.bin"});
//        ToolRunner.run(new HdfsWriter(), new String[]{"model/coefficients.bin",
//                "/user/hduser/mnist_model/coefficients.bin"});
    }
}
