package io.github.mandar2812.dynaml.examples

import breeze.linalg.{DenseMatrix, DenseVector}
import io.github.mandar2812.dynaml.evaluation.RegressionMetrics
import io.github.mandar2812.dynaml.kernels._
import io.github.mandar2812.dynaml.models.gp.GPRegression
import io.github.mandar2812.dynaml.optimization.{GPMLOptimizer, GridSearch}
import io.github.mandar2812.dynaml.pipes.{StreamDataPipe, DataPipe}
import io.github.mandar2812.dynaml.utils

import scala.util.Random

/**
  * Created by mandar on 19/11/15.
  */
object TestGPOmni {
  def apply (kern: String = "RBF",
             year: Int = 2006,
             yeartest: Int = 2007,
             bandwidth: Double = 0.5,
             noise: Double = 0.0,
             num_training: Int = 200,
             num_test: Int = 50,
             columns: List[Int] = List(40,16,21,23,24,22,25),
             grid: Int = 5,
             step: Double = 0.2,
             randomSample: Boolean = false,
             globalOpt: String = "ML",
             stepSize: Double, maxIt: Int): Unit = {

    val kernel: CovarianceFunction[DenseVector[Double], Double, DenseMatrix[Double]] =
      kern match {
        case "RBF" =>
          new RBFKernel(bandwidth)
        case "Cauchy" =>
          new CauchyKernel(bandwidth)
        case "Laplacian" =>
          new LaplacianKernel(bandwidth)
        case "RationalQuadratic" =>
          new RationalQuadraticKernel(bandwidth)
        case "FBM" => new FBMKernel(bandwidth)
        case "Student" => new TStudentKernel(bandwidth)
        case "Anova" => new AnovaKernel(bandwidth)
      }

      runExperiment(year, yeartest, kernel, bandwidth,
        noise, num_training, num_test, columns,
        grid, step, globalOpt, randomSample,
        Map("tolerance" -> "0.0001",
        "step" -> stepSize.toString,
        "maxIterations" -> maxIt.toString))

  }

  def apply (kernel: CovarianceFunction[DenseVector[Double], Double, DenseMatrix[Double]],
             year: Int, yeartest: Int,
             bandwidth: Double,
             noise: Double, num_training: Int,
             num_test: Int, columns: List[Int],
             grid: Int, step: Double,
             randomSample: Boolean,
             globalOpt: String,
             stepSize: Double,
             maxIt: Int): Unit = {

    runExperiment(year, yeartest, kernel, bandwidth,
      noise, num_training, num_test, columns,
      grid, step, globalOpt, randomSample,
      Map("tolerance" -> "0.0001",
        "step" -> stepSize.toString,
        "maxIterations" -> maxIt.toString))

  }

  def runExperiment(year: Int = 2006, yeartest: Int = 2007,
                    kernel: CovarianceFunction[DenseVector[Double], Double, DenseMatrix[Double]],
                    bandwidth: Double = 0.5, noise: Double = 0.0,
                    num_training: Int = 200, num_test: Int = 50,
                    columns: List[Int] = List(40,16,21,23,24,22,25),
                    grid: Int = 5, step: Double = 0.2,
                    globalOpt: String = "ML", randomSample: Boolean = false,
                    opt: Map[String, String]): Unit = {

    //Load Omni data into a stream
    //Extract the time and Dst values
    //separate data into training and test
    //pipe training data to model and then generate test predictions
    //create RegressionMetrics instance and produce plots

    val replaceWhiteSpaces = (s: Stream[String]) => s.map(utils.replace("\\s+")(","))

    val extractTrainingFeatures = (l: Stream[String]) =>
      utils.extractColumns(l, ",", columns,
        Map(16 -> "999.9", 21 -> "999.9",
          24 -> "9999.", 23 -> "999.9",
          40 -> "99999", 22 -> "9999999.",
          25 -> "999.9", 28 -> "99.99",
          27 -> "9.999", 39 -> "999"))


    val normalizeData =
      (trainTest: (Stream[(DenseVector[Double], Double)], Stream[(DenseVector[Double], Double)])) => {

        val (mean, variance) = utils.getStats(trainTest._1.map(tup =>
          DenseVector(tup._1.toArray ++ Array(tup._2))).toList)

        val stdDev: DenseVector[Double] = variance.map(v =>
          math.sqrt(v/(trainTest._1.length.toDouble - 1.0)))


        val normalizationFunc = (point: (DenseVector[Double], Double)) => {
          val extendedpoint = DenseVector(point._1.toArray ++ Array(point._2))

          val normPoint = (extendedpoint - mean) :/ stdDev
          val length = normPoint.length
          (normPoint(0 until length-1), normPoint(-1))
        }

        ((trainTest._1.map(normalizationFunc),
          trainTest._2.map(normalizationFunc)), (mean, stdDev))
      }

    val preProcessPipe = DataPipe(utils.textFileToStream _) >
      DataPipe(replaceWhiteSpaces) >
      DataPipe(extractTrainingFeatures) >
      StreamDataPipe((line: String) => !line.contains(",,")) >
      StreamDataPipe((line: String) => {
        val split = line.split(",")
        (DenseVector(split.tail.map(_.toDouble)), split.head.toDouble)
      })


    //function to train and test a GP Regression model
    //accepts training and test splits separately.
    val modelTrainTest =
      (trainTest: ((Stream[(DenseVector[Double], Double)],
        Stream[(DenseVector[Double], Double)]),
        (DenseVector[Double], DenseVector[Double]))) => {
        val model = new GPRegression(kernel, trainTest._1._1.toSeq).setNoiseLevel(noise)

        val gs = globalOpt match {
          case "GS" => new GridSearch[model.type](model)
            .setGridSize(grid)
            .setStepSize(step)
            .setLogScale(false)

          case "ML" => new GPMLOptimizer[DenseVector[Double],
            Seq[(DenseVector[Double], Double)],
            GPRegression](model)
        }

        val startConf = kernel.state ++ Map("noiseLevel" -> noise)
        val (_, conf) = gs.optimize(kernel.state + ("noiseLevel" -> noise), opt)

        model.setState(conf)

        val res = model.test(trainTest._1._2.toSeq)
        val scoresAndLabelsPipe =
          DataPipe(
            (res: Seq[(DenseVector[Double], Double, Double, Double, Double)]) =>
              res.map(i => (i._3, i._2)).toList) > DataPipe((list: List[(Double, Double)]) =>
            list.map{l => (l._1*trainTest._2._2(-1) + trainTest._2._1(-1),
              l._2*trainTest._2._2(-1) + trainTest._2._1(-1))})

        val scoresAndLabels = scoresAndLabelsPipe.run(res)

        val metrics = new RegressionMetrics(scoresAndLabels,
          scoresAndLabels.length)

        //println(scoresAndLabels)
        metrics.print()
        metrics.generatePlots()

      }

    /*
    * Create the final pipe composed as follows
    *
    * train, test
    *   |       |
    *   |-------|
    *   |       |
    *   v       v
    * p_train, p_test : pre-process
    *   |       |
    *   |-------|
    *   |       |
    *   v       v
    * s_train, s_test : sub-sample
    *   |       |
    *   |-------|
    *   |       |
    *   v       v
    * norm_tr, norm_te : mean center and standardize
    *   |       |
    *   |-------|
    *   |       |
    *   v       v
    *   |       |
    *  |-----------------|
    *  | Train, tune and |
    *  | test the model. |
    *  | Output graphs,  |
    *  | metrics         |
    *  |_________________|
    *
    * */
    val trainTestPipe = DataPipe(preProcessPipe, preProcessPipe) >
      DataPipe((data: (Stream[(DenseVector[Double], Double)],
        Stream[(DenseVector[Double], Double)])) => {
        if(!randomSample)
          (data._1.take(num_training), data._2.takeRight(num_test))
        else
          (data._1.filter(_ => Random.nextDouble() <= num_training/data._1.size.toDouble),
            data._2.filter(_ => Random.nextDouble() <= num_test/data._2.size.toDouble))
      }) >
      DataPipe(normalizeData) >
      DataPipe(modelTrainTest)


    trainTestPipe.run(("data/omni2_"+year+".csv", "data/omni2_"+yeartest+".csv"))
  }

}
