package sisdn.admission

import akka.actor._
import akka.persistence.fsm.PersistentFSM
import akka.persistence.fsm.PersistentFSM.FSMState
import com.typesafe.config.ConfigFactory
import sisdn.admission.AdmissionFSM._
import sisdn.common._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.reflect.{ClassTag, classTag}


class AdmissionFSM(id: String, validatorActor: ActorRef, processorActor: ActorRef)
  extends PersistentFSM[State, AdmissionData, AdmissionEvt] with ActorLogging {


  override def persistenceId: String = id

  override def domainEventClassTag: ClassTag[AdmissionEvt] = classTag[AdmissionEvt]

  implicit val ec = context.dispatcher
  implicit val Timeout = 3 seconds
  val validator = validatorActor
  val processor = processorActor
  val config = ConfigFactory.load().getConfig("sisdn.admission")
  //flag to facilitate testing
  val `Testing` = ConfigFactory.load().getBoolean("sisdn.testing")

  override def applyEvent(evt: AdmissionEvt, currentData: AdmissionData): AdmissionData = evt match {
    case SubmittedEvt(data) => data

    case ValidatedEvt(data) if data.valid =>
       NonEmptyAdmissionData(stateData.id, stateData.student.map(_.copy()), AdmissionStatus.Valid, "")
    case ValidatedEvt(data) if !data.valid =>
      NonEmptyAdmissionData(currentData.id, stateData.student.map(_.copy()), AdmissionStatus.Invalid, "")

    case ProcessedEvt(data) if data.status == AdmissionStatus.InProcessing =>
        NonEmptyAdmissionData(stateData.id, stateData.student.map(_.copy()), AdmissionStatus.InProcessing, "")
    case ProcessedEvt(data) if data.status == AdmissionStatus.Accepted =>
        NonEmptyAdmissionData(stateData.id, stateData.student.map(_.copy()), AdmissionStatus.Accepted, "")
    case ProcessedEvt(data) if data.status == AdmissionStatus.Rejected =>
        NonEmptyAdmissionData(stateData.id, stateData.student.map(_.copy()), AdmissionStatus.Rejected, data.remarks)
    }


  startWith(InitState, EmptyAdmissionData)


  when(InitState) {
    case Event(SubmittedEvt(data), _) => goto(PendingValidationState).
      applying(SubmittedEvt(data)) replying ACK andThen {
      case _ => stateData.student.foreach(validator ! _)
    }
  }


  when(PendingValidationState, stateTimeout =  config.getDuration("validationResponseTimeout")) {
    case Event(ValidatedEvt(data), stateData) if data.valid =>
       goto(ValidState).applying (ValidatedEvt(data))

    case Event(ValidatedEvt(data), stateData) if !data.valid =>
       goto(InvalidState) applying ValidatedEvt(data)

    case Event(StateTimeout, data) => goto(PendingValidationState) andThen {
      case_ => data.student.foreach(validator ! _)
    }
  }


  when(ValidState, config.getDuration("processingAckTimeout")) {
    case Event(evt @ ProcessedEvt(data), _) if data.status == AdmissionStatus.InProcessing =>
        goto(InProcessingState) applying evt

    case Event(StateTimeout, data) => goto(ValidState) andThen {
      case_ => data.student.foreach(processor ! _)
    }
  }


  //TODO need to decide what to do if admission was invalid
  when(InvalidState, config.getDuration("invalidStateDUration")){
    case Event(ACK, _) => stop()
  }


  when(InProcessingState, config.getDuration("processingResponseTimeout")){
    case Event(evt @ ProcessedEvt(data), stateData)
      if data.status == AdmissionStatus.Accepted => goto (AcceptedState) applying evt
    case Event(evt @ ProcessedEvt(data), stateData)
      if data.status == AdmissionStatus.Rejected => goto (RejectedState) applying evt

    case Event(StateTimeout, data) => goto(InProcessingState) andThen {
      case _ => data.student.foreach(processor ! _)
    }
  }


  //TODO had to put "ACK" so they don't intercept all events
  when(RejectedState){ case Event(ACK,_) => stay()}
  when(AcceptedState){ case Event(ACK,_) => stay()}


  whenUnhandled{
      case Event(e,s) if `Testing` && e == "state" => stay replying s
  }


  initialize()
}

object AdmissionFSM {
  def props(id: String, validator: ActorRef, processor: ActorRef) =
    Props(classOf[AdmissionFSM], id, validator, processor)

  sealed trait State extends FSMState

  case object InitState extends State {
    override def identifier: String = "InitState"
  }

  case object PendingValidationState extends State {
    override def identifier: String = "PendingValidationState"
  }

  case object InvalidState extends State {
    override def identifier: String = "InvalidState"
  }

  case object ValidState extends State {
    override def identifier: String = "PendingProcessingState"
  }

  case object InProcessingState extends State {
    override def identifier: String = "InProcessingState"
  }

  case object AcceptedState extends State {
    override def identifier: String = "AcceptedState"
  }

  case object AdmittedState extends State {
    override def identifier: String = "AdmittedState"
  }

  case object RejectedState extends State {
    override def identifier: String = "RejectedState"
  }
}
