package org.myrobotlab.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.myrobotlab.framework.Service;
import org.myrobotlab.framework.ServiceType;
import org.myrobotlab.genetic.GeneticAlgorithm;
import org.myrobotlab.genetic.Chromosome;
import org.myrobotlab.genetic.Genetic;
import org.myrobotlab.kinematics.DHLink;
import org.myrobotlab.kinematics.DHRobotArm;
import org.myrobotlab.kinematics.Matrix;
import org.myrobotlab.kinematics.Point;
import org.myrobotlab.logging.Level;
import org.myrobotlab.logging.LoggerFactory;
import org.myrobotlab.logging.LoggingFactory;
import org.myrobotlab.math.MathUtils;
import org.myrobotlab.service.data.JoystickData;
import org.myrobotlab.service.interfaces.IKJointAnglePublisher;
import org.myrobotlab.service.interfaces.PointsListener;
import org.slf4j.Logger;

/**
 * 
 * InverseKinematics3D - This class provides a 3D based inverse kinematics
 * implementation that allows you to specify the robot arm geometry based on DH
 * Parameters. This will use a pseudo-inverse jacobian gradient descent approach
 * to move the end affector to the desired x,y,z postions in space with respect
 * to the base frame.
 * 
 * Rotation and Orientation information is not currently supported. (but should
 * be easy to add)
 *
 * @author kwatters
 * 
 */
public class InverseKinematics3D extends Service implements IKJointAnglePublisher, PointsListener, Genetic {

  private static final long serialVersionUID = 1L;
  public final static Logger log = LoggerFactory.getLogger(InverseKinematics3D.class.getCanonicalName());

  private DHRobotArm currentArm = null;

  // we will track the joystick input to specify our velocity.
  private Point joystickLinearVelocity = new Point(0, 0, 0, 0, 0, 0);

  private Matrix inputMatrix = null;

  transient InputTrackingThread trackingThread = null;
  
  Point goTo;

  public InverseKinematics3D(String n) {
    super(n);
    // TODO: init
  }

  public void startTracking() {
    log.info(String.format("startTracking - starting new joystick input tracking thread %s_tracking", getName()));
    if (trackingThread != null) {
      stopTracking();
    }
    trackingThread = new InputTrackingThread(String.format("%s_tracking", getName()));
    trackingThread.start();
  }

  public void stopTracking() {
    if (trackingThread != null) {
      trackingThread.setTracking(false);
    }
  }

  public class InputTrackingThread extends Thread {

    private boolean isTracking = false;

    public InputTrackingThread(String name) {
      super(name);
    }

    @Override
    public void run() {

      // Ok, here we are. if we're running..
      // we should be updating the move to based on the velocities
      // that are being tracked with the joystick.

      // how many ms to wait between movements.
      long pollInterval = 250;

      isTracking = true;
      long now = System.currentTimeMillis();
      while (isTracking) {
        long pause = now + pollInterval - System.currentTimeMillis();
        try {
          // the number of milliseconds until we update the position
          Thread.sleep(pause);
        } catch (InterruptedException e) {
          // TODO Auto-generated catch block
          log.info("Interrupted tracking thread.");
          e.printStackTrace();
          isTracking = false;
        }
        // lets get the current position
        // current position + velocity * time
        Point current = currentPosition();
        Point targetPoint = current.add(joystickLinearVelocity.multiplyXYZ(pollInterval / 1000.0));
        if (!targetPoint.equals(current)) {
          log.info("Velocity: {} Old: {} New: {}", joystickLinearVelocity, current, targetPoint);
        }

        invoke("publishTracking", targetPoint);
        moveTo(targetPoint);
        // update current timestamp to determine how long we should wait
        // before the next moveTo is called.
        now = System.currentTimeMillis();
      }

    }

    public boolean isTracking() {
      return isTracking;
    }

    public void setTracking(boolean isTracking) {
      this.isTracking = isTracking;
    }
  }

  public Point currentPosition() {
    return currentArm.getPalmPosition();
  }

  public void moveTo(double x, double y, double z) {
    // TODO: allow passing roll pitch and yaw
    moveTo(new Point(x, y, z, 0, 0, 0));
  }

  /**
   * This create a rotation and translation matrix that will be applied on the
   * "moveTo" call.
   * 
   * @param dx
   *          - x axis translation
   * @param dy
   *          - y axis translation
   * @param dz
   *          - z axis translation
   * @param roll
   *          - rotation about z (in degrees)
   * @param pitch
   *          - rotation about x (in degrees)
   * @param yaw
   *          - rotation about y (in degrees)
   * @return
   */
  public Matrix createInputMatrix(double dx, double dy, double dz, double roll, double pitch, double yaw) {
    roll = MathUtils.degToRad(roll);
    pitch = MathUtils.degToRad(pitch);
    yaw = MathUtils.degToRad(yaw);
    Matrix trMatrix = Matrix.translation(dx, dy, dz);
    Matrix rotMatrix = Matrix.zRotation(roll).multiply(Matrix.yRotation(yaw).multiply(Matrix.xRotation(pitch)));
    inputMatrix = trMatrix.multiply(rotMatrix);
    return inputMatrix;
  }

  public Point rotateAndTranslate(Point pIn) {

    Matrix m = new Matrix(4, 1);
    m.elements[0][0] = pIn.getX();
    m.elements[1][0] = pIn.getY();
    m.elements[2][0] = pIn.getZ();
    m.elements[3][0] = 1;
    Matrix pOM = inputMatrix.multiply(m);

    // TODO: compute the roll pitch yaw
    double roll = 0;
    double pitch = 0;
    double yaw = 0;

    Point pOut = new Point(pOM.elements[0][0], pOM.elements[1][0], pOM.elements[2][0], roll, pitch, yaw);
    return pOut;
  }

  public void centerAllJoints() {
    currentArm.centerAllJoints();
    publishTelemetry();
  }

  public void moveTo(Point p) {

    // log.info("Move TO {}", p );
    if (inputMatrix != null) {
      p = rotateAndTranslate(p);
    }
    boolean success = currentArm.moveToGoal(p);

    if (success) {
      publishTelemetry();
    }
  }

  public void publishTelemetry() {
    Map<String, Double> angleMap = new HashMap<String, Double>();
    for (DHLink l : currentArm.getLinks()) {
      String jointName = l.getName();
      double theta = l.getTheta();
      // angles between 0 - 360 degrees.. not sure what people will really want?
      // - 180 to + 180 ?
      angleMap.put(jointName, (double) MathUtils.radToDeg(theta) % 360.0F);
    }
    invoke("publishJointAngles", angleMap);
    // we want to publish the joint positions
    // this way we can render on the web gui..
    double[][] jointPositionMap = createJointPositionMap();
    // TODO: pass a better datastructure?
    invoke("publishJointPositions", (Object) jointPositionMap);
  }

  public double[][] createJointPositionMap() {

    double[][] jointPositionMap = new double[currentArm.getNumLinks() + 1][5];

    // first position is the origin... second is the end of the first link
    jointPositionMap[0][0] = 0;
    jointPositionMap[0][1] = 0;
    jointPositionMap[0][2] = 0;

    for (int i = 1; i <= currentArm.getNumLinks(); i++) {
      Point jp = currentArm.getJointPosition(i - 1);
      jointPositionMap[i][0] = jp.getX();
      jointPositionMap[i][1] = jp.getY();
      jointPositionMap[i][2] = jp.getZ();
    }
    return jointPositionMap;
  }

  public DHRobotArm getCurrentArm() {
    return currentArm;
  }

  public void setCurrentArm(DHRobotArm currentArm) {
    this.currentArm = currentArm;
  }

  public static void main(String[] args) throws Exception {
    LoggingFactory.getInstance().configure();
    LoggingFactory.getInstance().setLevel(Level.INFO);

    Runtime.createAndStart("python", "Python");
    Runtime.createAndStart("gui", "GUIService");

    InverseKinematics3D inversekinematics = (InverseKinematics3D) Runtime.start("ik3d", "InverseKinematics3D");
    // InverseKinematics3D inversekinematics = new InverseKinematics3D("iksvc");
    inversekinematics.setCurrentArm(InMoovArm.getDHRobotArm());
    //
    inversekinematics.getCurrentArm().setIk3D(inversekinematics);
    // Create a new DH Arm.. simpler for initial testing.
    // d , r, theta , alpha
    // DHRobotArm testArm = new DHRobotArm();
    // testArm.addLink(new DHLink("one" ,400,0,0,90));
    // testArm.addLink(new DHLink("two" ,300,0,0,90));
    // testArm.addLink(new DHLink("three",200,0,0,0));
    // testArm.addLink(new DHLink("two", 0,0,0,0));
    // inversekinematics.setCurrentArm(testArm);
    // set up our input translation/rotation
    //
    // if (false) {
    // double dx = 400.0;
    // double dy = -600.0;
    // double dz = -350.0;
    // double roll = 0.0;
    // double pitch = 0.0;
    // double yaw = 0.0;
    // inversekinematics.createInputMatrix(dx, dy, dz, roll, pitch, yaw);
    // }

    // Rest position...
    // Point rest = new Point(100,-300,0,0,0,0);
    // rest.
    // inversekinematics.moveTo(rest);

    // LeapMotion lm = (LeapMotion)Runtime.start("leap", "LeapMotion");
    // lm.addPointsListener(inversekinematics);

    boolean attached = true;
    if (attached) {
      // set up the left inmoov arm
      InMoovArm leftArm = (InMoovArm) Runtime.start("leftArm", "InMoovArm");
      leftArm.connect("COM21");
      // leftArm.omoplate.setMinMax(0, 180);
      // attach the publish joint angles to the on JointAngles for the inmoov
      // arm.
      inversekinematics.addListener("publishJointAngles", leftArm.getName(), "onJointAngles");
    }

    // Runtime.createAndStart("gui", "GUIService");
    // OpenCV cv1 = (OpenCV)Runtime.createAndStart("cv1", "OpenCV");
    // OpenCVFilterAffine aff1 = new OpenCVFilterAffine("aff1");
    // aff1.setAngle(270);
    // aff1.setDx(-80);
    // aff1.setDy(-80);
    // cv1.addFilter(aff1);
    //
    // cv1.setCameraIndex(0);
    // cv1.capture();
    // cv1.undockDisplay(true);

    /*
     * GUIService gui = new GUIService("gui"); gui.startService();
     */

    Joystick joystick = (Joystick) Runtime.start("joystick", "Joystick");
    joystick.setController(2);

    // joystick.startPolling();

    // attach the joystick input to the ik3d service.
    joystick.addInputListener(inversekinematics);

    Runtime.start("webgui", "WebGui");
    Runtime.start("log", "Log");
  }

  @Override
  public Map<String, Double> publishJointAngles(HashMap<String, Double> angleMap) {
    // TODO Auto-generated method stub
    return angleMap;
  }

  public double[][] publishJointPositions(double[][] jointPositionMap) {
    return jointPositionMap;
  }

  public Point publishTracking(Point tracking) {
    return tracking;
  }

  @Override
  public void onPoints(List<Point> points) {
    // TODO : move input matrix translation to here? or somewhere?
    // TODO: also don't like that i'm going to just say take the first point
    // now.
    // TODO: points should probably be a map, each point should have a name ?
    moveTo(points.get(0));
  }

  public void onJoystickInput(JoystickData input) {

    // a few control button pushes
    // Ok, lets say the the "a" button starts tracking
    if ("0".equals(input.id)) {
      log.info("Start Tracking button pushed.");
      startTracking();
    } else if ("1".equals(input.id)) {
      stopTracking();
    }
    // and the "b" button stops tracking
    // TODO: use the joystick input to drive the "moveTo" command.
    // TODO: joystick listener interface?
    // input.id
    // input.value
    // depending on input we want to get the current position and move in some
    // direction.
    // or potentially stay in the same place..
    // we start at the origin
    // initially at rest.
    // we can set the velocities to be equal to the joystick inputs
    // with some gain/amplification.
    // Ok, so this will track the y,rx,ry inputs from the joystick as x,y,z
    // velocities

    // we want to have a minimum threshold o/w we set the value to zero
    // quantize
    float threshold = 0.1F;
    if (Math.abs(input.value) < threshold) {
      input.value = 0.0F;
    }

    double totalGain = 100.0;
    double xGain = totalGain;
    // invert y control.
    double yGain = -1.0 * totalGain;
    double zGain = totalGain;
    if ("x".equals(input.id)) {
      // x axis control (left/right)
      joystickLinearVelocity.setX(input.value * xGain);
    } else if ("y".equals(input.id)) {
      // y axis control (up/down)
      joystickLinearVelocity.setY(input.value * yGain);
    }
    if ("ry".equals(input.id)) {
      // z axis control (forward / backwards)
      joystickLinearVelocity.setZ(input.value * zGain);
    }
    // log.info("Linear Velocity : {}", joystickLinearVelocity);
    // on a loop I want to sample the current joystickLinearVelocity
    // at some interval and move the current position by the new dx,dy,dz
    // computed based
    // off the input from the joystick.
    // relying on the current position is probably bad.
    // TODO: track the desired position independently of the current position.
    // we will allow translation, x,y,z
    // for the input point.
  }

  /**
   * This static method returns all the details of the class without it having
   * to be constructed. It has description, categories, dependencies, and peer
   * definitions.
   * 
   * @return ServiceType - returns all the data
   * 
   */
  static public ServiceType getMetaData() {

    ServiceType meta = new ServiceType(InverseKinematics3D.class.getCanonicalName());
    meta.addDescription("a 3D kinematics service supporting D-H parameters");
    meta.addCategory("robot", "control");

    return meta;
  }

  public void setDHLink (String name, double d, double theta, double r, double alpha) {
    DHLink dhLink = new DHLink(name, d, r, MathUtils.degToRad(theta), MathUtils.degToRad(alpha));
    currentArm.addLink(dhLink);
  }
  
  public void setDHLink (Servo servo, double d, double theta, double r, double alpha) {
    DHLink dhLink = new DHLink(servo.getName(), d, r, MathUtils.degToRad(theta), MathUtils.degToRad(alpha));
    dhLink.setServo(servo);
    currentArm.addLink(dhLink);
  }
  
  public void setNewDHRobotArm() {
    currentArm = new DHRobotArm();
  }
  
 
  
  public void moveTo(int x, int y, int z) {
    goTo = new Point((double)x,(double)y,(double)z,0.0,0.0,0.0);
    GeneticAlgorithm GA = new GeneticAlgorithm(this, 100, currentArm.getNumLinks(), 8, 0.9, 0.01);
    GA.setGeneticClass(this);
    Chromosome bestFit = GA.doGeneration(500);
    //insert collision detection code here
   // DHRobotArm checkedArm = simulateMove(bestFit.getDecodedGenome());
    for (int i = 0; i < currentArm.getNumLinks(); i++){
      //currentArm.getLink(i).servo.moveTo(((Double)(checkedArm.getLink(i).getThetaDegrees()-currentArm.getLink(i).getThetaDegrees())).intValue());
      currentArm.getLink(i).servo.moveTo(((Double)bestFit.getDecodedGenome().get(i)).intValue());
    }
  }

  private DHRobotArm simulateMove(ArrayList<Object> decodedGenome) {
    // TODO Auto-generated method stub
    double time = 0.2;
    boolean isMoving = true;
    DHRobotArm oldArm = currentArm;
    while (isMoving) {
      isMoving = false;
      DHRobotArm newArm = new DHRobotArm();
      log.info("time: {}", time);
      for (int i = 0; i < currentArm.getNumLinks(); i++) {
        DHLink newLink = new DHLink(currentArm.getLink(i));
        double degrees=currentArm.getLink(i).servo.getPos().doubleValue();
        double deltaDegree = java.lang.Math.abs(degrees - (Double)decodedGenome.get(i));
        double deltaDegree2 = time * (Integer)currentArm.getLink(i).servo.getVelocity();
        if (deltaDegree < 0.0){
          deltaDegree *= -1;
        }
        if (deltaDegree > deltaDegree2) {
          deltaDegree = deltaDegree2;
          isMoving = true;
        }
        if (degrees > ((Double)decodedGenome.get(i)).intValue()) {
          degrees -= deltaDegree;
        }
        else if (degrees < ((Double)decodedGenome.get(i)).intValue()) {
          degrees += deltaDegree;
        }
        log.info("{}:{}",currentArm.getLink(i).getThetaDegrees(), newLink.getThetaDegrees());
        newLink.setTheta(newLink.getTheta() + MathUtils.degToRad(degrees));
        log.info("{}",newLink.getThetaDegrees());
        newArm.addLink(newLink);
        log.info("{}: {}", currentArm.getLink(i).getName(), degrees);
      }
      double[][] jp = createJointPositionMap();
      boolean collision = false;
      //send data to the collision detector class
      if (collision ) return oldArm;
      else oldArm = newArm;
      time += 0.2;
    }
    return oldArm;
  }

  @Override
  public void calcFitness(ArrayList<Chromosome> pool) {
    // TODO Auto-generated method stub
    for (Chromosome chromosome : pool) {
      DHRobotArm arm = new DHRobotArm();
      double fitnessMult = 1;
      for (int i = 0; i < currentArm.getNumLinks(); i++){
        DHLink newLink = new DHLink(currentArm.getLink(i));
        newLink.setTheta(newLink.getTheta()+MathUtils.degToRad((double)chromosome.getDecodedGenome().get(i)));
        Double delta = currentArm.getLink(i).servo.getPos()-(Double)chromosome.getDecodedGenome().get(i);
        if (delta == 0) {
          fitnessMult +=Math.sqrt(currentArm.getLink(i).servo.getVelocity()*currentArm.getLink(i).servo.getSpeed()/10);
        }
        else {
          fitnessMult += Math.sqrt(currentArm.getLink(i).servo.getVelocity()*currentArm.getLink(i).servo.getSpeed()/10)/delta;
          //fitnessMult *= 1/10;
        }
        arm.addLink(newLink);
      }
      Point potLocation = arm.getPalmPosition();
      Double distance = Math.sqrt(Math.pow(potLocation.getX()-goTo.getX(), 2)+Math.pow(potLocation.getY()-goTo.getY(), 2) +  Math.pow(potLocation.getZ()-goTo.getZ(), 2));
      //fitnessMult *= Math.pow(0.01, currentArm.getNumLinks());
      Double fitness = fitnessMult/distance*1000;
      if (fitness < 0) fitness *=-1;
      chromosome.setFitness(fitness);
    }
    return;
  }

  @Override
  public void decode(ArrayList<Chromosome> chromosomes) {
    // TODO Auto-generated method stub
    for (Chromosome chromosome : chromosomes ){
      int pos=0;
      ArrayList<Object>decodedGenome = new ArrayList<Object>();
      for (DHLink link: currentArm.getLinks()){
        Double value=0.0;
        for (int i= pos; i< chromosome.getGenome().length() && i < pos+8; i++){
          if(chromosome.getGenome().charAt(i) == '1') value += 1 << i-pos; 
        }
        pos += 8;
        if (value < link.servo.getMinInput()) value = (double)link.servo.targetPos;
        if (value > link.servo.getMaxInput()) value = (double)link.servo.targetPos;
        decodedGenome.add(value);
      }
      chromosome.setDecodedGenome(decodedGenome);
    }
  }
  
}
