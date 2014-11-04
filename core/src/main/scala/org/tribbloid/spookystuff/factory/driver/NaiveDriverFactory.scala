package org.tribbloid.spookystuff.factory.driver

import java.util.concurrent.TimeUnit

import org.openqa.selenium.phantomjs.{PhantomJSDriver, PhantomJSDriverService}
import org.openqa.selenium.remote.{CapabilityType, DesiredCapabilities}
import org.openqa.selenium.{Dimension, Capabilities, WebDriver}
import org.tribbloid.spookystuff.{SpookyContext, Const, Utils}

/**
 * Created by peng on 25/07/14.
 */
class NaiveDriverFactory(
                          phantomJSPath: String,
                          loadImages: Boolean,
                          userAgent: String,
                          resolution: (Int,Int)
                          )
  extends DriverFactory {

  //  val phantomJSPath: String

  val baseCaps = new DesiredCapabilities
  baseCaps.setJavascriptEnabled(true);                //< not really needed: JS enabled by default
  baseCaps.setCapability(CapabilityType.SUPPORTS_FINDING_BY_CSS,true)
  baseCaps.setCapability("takesScreenshot", true)
  baseCaps.setCapability(PhantomJSDriverService.PHANTOMJS_EXECUTABLE_PATH_PROPERTY, phantomJSPath)
  baseCaps.setCapability(PhantomJSDriverService.PHANTOMJS_PAGE_SETTINGS_PREFIX+"loadImages", loadImages)
  if (userAgent!=null) baseCaps.setCapability(PhantomJSDriverService.PHANTOMJS_PAGE_SETTINGS_PREFIX+"userAgent", userAgent)
  //  baseCaps.setCapability(PhantomJSDriverService.PHANTOMJS_PAGE_SETTINGS_PREFIX+"resourceTimeout", Const.resourceTimeout*1000)

  def newCap(capabilities: Capabilities) = baseCaps.merge(capabilities)

  override def newInstance(capabilities: Capabilities, spooky: SpookyContext): WebDriver = {

    //TODO: this is a browser leakage loose end, switching to resource pool!
    val driver = Utils.retryWithDeadline(Const.inPartitionRetry, Const.sessionInitializationTimeout) {
      new PhantomJSDriver(newCap(capabilities))
    }

    try {
      Utils.retryWithDeadline(Const.inPartitionRetry, Const.sessionInitializationTimeout) {
        driver.manage().timeouts()
          .implicitlyWait(spooky.remoteResourceTimeout.toSeconds, TimeUnit.SECONDS)
          .pageLoadTimeout(spooky.remoteResourceTimeout.toSeconds, TimeUnit.SECONDS)
          .setScriptTimeout(spooky.remoteResourceTimeout.toSeconds, TimeUnit.SECONDS)
        if (resolution != null) driver.manage().window().setSize(new Dimension(resolution._1, resolution._2))

        driver
      }
    }
    catch {
      case e: Throwable =>
        driver.close()
        driver.quit()
        throw e
    }
  }
}

object NaiveDriverFactory {

  def apply(
             phantomJSPath: String = Const.phantomJSPath,
             loadImages: Boolean = false,
             userAgent: String = Const.userAgent,
             resolution: (Int,Int) = (1920, 1080)
             ) = new NaiveDriverFactory(phantomJSPath, loadImages, userAgent, resolution)
}

//case class NaiveDriverFactory(phantomJSPath: String) extends NaiveDriverFactory(phantomJSPath)
