package akka.io

import com.sun.nio.sctp.{ SctpChannel, SctpServerChannel, SctpMultiChannel }

object SctpInet {

  /**
   * SctpSocketOption is a package of data (from the user) and associated
   * behavior (how to apply that to a channel).
   */
  trait SctpSocketOption {

    /**
     * Action to be taken for this option before bind() is called
     */
    def beforeBind(ds: SctpChannel): Unit = ()

    /**
     * Action to be taken for this option before bind() is called
     */
    def beforeBind(ss: SctpServerChannel): Unit = ()

    /**
     * Action to be taken for this option before bind() is called
     */
    def beforeBind(s: SctpMultiChannel): Unit = ()

    /**
     * Action to be taken for this option after connect returned (i.e. on
     * the slave socket for servers).
     */
    def afterConnect(c: SctpChannel): Unit = ()

    /**
     * Action to be taken for this option after connect returned (i.e. on
     * the slave socket for servers).
     */
    def afterConnect(c: SctpServerChannel): Unit = ()

    /**
     * Action to be taken for this option after connect returned (i.e. on
     * the slave socket for servers).
     */
    def afterConnect(c: SctpMultiChannel): Unit = ()
  }

  /**
   * SctpChannel creation behavior.
   */
  class SctpChannelCreator extends SctpSocketOption {

    /**
     * Open and return new SctpChannel.
     *
     * [[scala.throws]] is needed because [[SctpChannel.open]] method
     * can throw an exception.
     */
    @throws(classOf[Exception])
    def create(): SctpChannel = SctpChannel.open()
  }

  object SctpChannelCreator {
    val default = new SctpChannelCreator()
    def apply() = default
  }

  /**
   * Java API: AbstractSctpSocketOption is a package of data (from the user) and associated
   * behavior (how to apply that to a channel).
   */
  abstract class AbstractSctpSocketOption extends SctpSocketOption {

    /**
     * Action to be taken for this option before bind() is called
     */
    override def beforeBind(ds: SctpChannel): Unit = ()

    /**
     * Action to be taken for this option before bind() is called
     */
    override def beforeBind(ss: SctpServerChannel): Unit = ()

    /**
     * Action to be taken for this option before bind() is called
     */
    override def beforeBind(s: SctpMultiChannel): Unit = ()

    /**
     * Action to be taken for this option after connect returned (i.e. on
     * the slave socket for servers).
     */
    override def afterConnect(c: SctpChannel): Unit = ()

    /**
     * Action to be taken for this option after connect returned (i.e. on
     * the slave socket for servers).
     */
    override def afterConnect(c: SctpServerChannel): Unit = ()

    /**
     * Action to be taken for this option after connect returned (i.e. on
     * the slave socket for servers).
     */
    override def afterConnect(c: SctpMultiChannel): Unit = ()
  }

  object SO {
  }

  trait SoForwarders {
  }

  trait SoJavaFactories {
    import SO._
  }
}
