package io.github.mandar2812.dynaml.evaluation

import breeze.linalg.DenseVector
import com.quantifind.charts.Highcharts._
import org.apache.log4j.{Priority, Logger}
import org.apache.spark.Accumulator
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD

import scalax.chart.module.ChartFactories.{XYBarChart, XYLineChart, XYAreaChart}

/**
 * Class implementing the calculation
 * of regression performance evaluation
 * metrics
 *
 * */

class RegressionMetricsSpark(protected val scores: RDD[(Double, Double)],
                             val len: Long)
  extends Metrics[Double] {

  override protected val scoresAndLabels = List()
  private val logger = Logger.getLogger(this.getClass)
  val length = len

  val (mae, rmse, rsq, rmsle):(Double, Double, Double, Double) = 
    RegressionMetricsSpark.computeKPIs(scores, length)
  
  def residuals() = this.scores.map((s) => (s._1 - s._2, s._2))

  def scores_and_labels() = this.scoresAndLabels

  override def print(): Unit = {
    logger.log(Priority.INFO, "Regression Model Performance")
    logger.log(Priority.INFO, "============================")
    logger.log(Priority.INFO, "MAE: " + mae)
    logger.log(Priority.INFO, "RMSE: " + rmse)
    logger.log(Priority.INFO, "RMSLE: " + rmsle)
    logger.log(Priority.INFO, "R^2: " + rsq)
  }

  override def kpi() = DenseVector(mae, rmse, rsq)

  override def generatePlots(): Unit = {
    implicit val theme = org.jfree.chart.StandardChartTheme.createDarknessTheme
    val residuals = this.residuals().map(_._1).collect().toList

    logger.log(Priority.INFO, "Generating Plot of Residuals")
    /*val chart1 = XYBarChart(roccurve,
      title = "Residuals", legend = true)

    chart1.show()*/
    histogram(residuals, numBins = 20)
    title("Histogram of Regression Residuals")
  }

}

object RegressionMetricsSpark {

  def computeKPIs(scoresAndLabels: RDD[(Double, Double)], size: Long)
  : (Double, Double, Double, Double) = {
    val mean: Accumulator[Double] = scoresAndLabels.context.accumulator(0.0, "mean")

    val err:DenseVector[Double] = scoresAndLabels.map((sc) => {
      val diff = sc._1 - sc._2
      mean += sc._2
      val difflog = math.pow(math.log(1 + math.abs(sc._1)) - math.log(math.abs(sc._2) + 1),
        2)
      DenseVector(math.abs(diff), math.pow(diff, 2.0), difflog)
    }).reduce((a,b) => a+b)

    val SS_res = err(1)

    val mu: Broadcast[Double] = scoresAndLabels.context.broadcast(mean.value/size.toDouble)

    val SS_tot = scoresAndLabels.map((sc) => math.pow(sc._2 - mu.value, 2.0)).sum()

    val rmse = math.sqrt(SS_res/size.toDouble)
    val mae = err(0)/size.toDouble
    val rsq = if(1/SS_tot != Double.NaN) 1 - (SS_res/SS_tot) else 0.0
    val rmsle = err(2)/size.toDouble
    (mae, rmse, rsq, rmsle)
  } 
  
}
