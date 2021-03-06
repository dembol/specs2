package org.specs2
package scalacheck

import org.scalacheck.util.Pretty._
import org.scalacheck.util.{FreqMap, Pretty}
import org.scalacheck.{Gen, Properties, Prop, Test}
import execute._
import matcher._
import PrettyDetails._

trait ScalaCheckPropertyCheck extends ExpectationsCreation {

  /**
   * checks if the property is true for each generated value, and with the specified
   * parameters
   */
  def check(prop: Prop, parameters: Parameters, prettyFreqMap: FreqMap[Set[Any]] => Pretty): Result = {
    // check the property with ScalaCheck
    // first add labels if this Prop is a composite one
    val prop1 = prop match {
      case ps: Properties =>
        new Prop {
          override def apply(params: Gen.Parameters): Prop.Result =
            Prop.all(ps.properties.map { case (n, p) => p :| n }:_*)(params)
        }
      case _ => prop
    }
    val result = Test.check(parameters.testParameters, prop1)
    val prettyTestResult = prettyResult(result, prettyFreqMap)(parameters.prettyParams)
    val testResult = if (parameters.prettyParams.verbosity == 0) "" else prettyTestResult

    val checkResult =
      result match {
        case Test.Result(Test.Passed, succeeded, discarded, fq, _)     =>
          Success(prettyTestResult, testResult, succeeded)

        case Test.Result(Test.Proved(as), succeeded, discarded, fq, _) =>
          Success(prettyTestResult, testResult, succeeded)

        case Test.Result(Test.Exhausted, n, _, fq, _)              =>
          Failure(prettyTestResult)

        case Test.Result(Test.Failed(args, labels), n, _, fq, _) =>
          new Failure(prettyTestResult, details = collectDetails(fq)) {
            // the location is already included in the failure message
            override def location = ""
          }

        case Test.Result(Test.PropException(args, ex, labels), n, _, fq, _) =>
          ex match {
            case FailureException(f) =>
              // in that case we want to represent a normal failure
              val failedResult = prettyResult(result.copy(status = Test.Failed(args, labels)), prettyFreqMap)(parameters.prettyParams)
              new Failure(failedResult + "\n> " + f.message, details = f.details) { override def location = f.location }
            case SkipException(s)    => s
            case PendingException(p) => p
            case e: java.lang.Exception      =>
              Error(prettyTestResult + showCause(e), e)
            case throwable    => throw ex
          }
      }

    checkResultFailure(checkResult)
  }

  /** @return the cause of the exception as a String if there is one */
  def showCause(e: java.lang.Exception) =
    Option(e.getCause).map(s"\n> caused by "+_).getOrElse("")

  def frequencies(fq: FreqMap[Set[Any]], parameters: Parameters, prettyFreqMap: FreqMap[Set[Any]] => Pretty) = {
    val noCollectedValues = parameters.prettyParams.verbosity <= 0 || fq.getRatios.map(_._1).forall(_.toSet == Set(()))
    if (noCollectedValues) ""
    else "\n" ++ prettyFreqMap(removeDetails(fq))(parameters.prettyParams)
  }

  /** copied from ScalaCheck to be able to inject the proper freqMap pretty */
  def prettyResult(res: Test.Result, freqMapPretty: FreqMap[Set[Any]] => Pretty) = Pretty { prms =>
    def labels(ls: scala.collection.immutable.Set[String]) =
      if(ls.isEmpty) ""
      else "> Labels of failing property: " / ls.mkString("\n")
    val s = res.status match {
      case Test.Proved(args) => "OK, proved property."/prettyArgs(args)(prms)
      case Test.Passed => "OK, passed "+res.succeeded+" tests."
      case Test.Failed(args, l) =>
        "Falsified after "+res.succeeded+" passed tests."/labels(l)/prettyArgs(args)(prms)
      case Test.Exhausted =>
        "Gave up after only "+res.succeeded+" passed tests. " +
          res.discarded+" tests were discarded."
      case Test.PropException(args,e,l) =>
        "Exception raised on property evaluation."/labels(l)/prettyArgs(args)(prms)/
          "> Exception: "+pretty(e,prms)
    }
    val t = if(prms.verbosity <= 1) "" else "Elapsed time: "+prettyTime(res.time)
    val map = freqMapPretty(res.freqMap).apply(prms)
    s/t/map
  }


}
