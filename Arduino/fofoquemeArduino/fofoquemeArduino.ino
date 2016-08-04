#include <Servo.h> 
#define NUM_MOTORS 10
#define DELAY_SLOW 30
#define DELAY_FAST 10

// states
#define STATE_WAIT -1
#define STATE_WRITE 10
#define STATE_REPOS 11

// new states
#define STATE_READ_WRITE 20
#define STATE_FOFOQUEME 22

// list of pins to use for the motors
//   in the order in which the arms will move
int servoPins[NUM_MOTORS] ={
  2,3,4,5,6,7,8,9,10,11};
// list of pins to use for the light
//   in the order in which the lights will turn on and off
int lightPins[NUM_MOTORS/2] ={
  A4,A3,A2,A1,A0};

// servos
Servo myServos[NUM_MOTORS];

// angle values for the read/write/center positions of each motor
int readPos[NUM_MOTORS] = {
  115,0,115,0,118,0,115,0,115,0};
int centerPos[NUM_MOTORS] = {
  90,90,90,90,90,90,90,90,90,90};
int writePos[NUM_MOTORS] = {
  70,180,75,180,80,180,70,180,70,180};

// to keep track of current position and desired position for each motor
int currPos[NUM_MOTORS] = {  
  0,0,0,0,0,0,0,0,0,0};
int targetPos[NUM_MOTORS] = {  
  0,0,0,0,0,0,0,0,0,0};

// for reading a byte from serial connection
int inByte = 0;
// to keep track of motor update times
unsigned long lastTime;
// current state
int currState;
// current motor being moved
int currWriteMotor;
// number of times we've fofoqued
int fofoquemeCnt;
// speed of arm movement is inversely proportional to this
int updateDelay;
int fofoqueDelay;

void setup() { 
  // for bluetooth communication
  Serial.begin(57600);

  // attach servos
  for(int i=0; i<NUM_MOTORS; i++) {
    myServos[i].attach(servoPins[i]);
  }

  // initial conditions
  lastTime = millis();
  currState = STATE_WAIT;
  currWriteMotor = 0;
  fofoquemeCnt = 0;
  updateDelay = DELAY_FAST;
  fofoqueDelay = DELAY_FAST;

  // send to start position
  // this also updates/resets the position arrays (currPos, targetPos)
  for(int i=0; i<NUM_MOTORS; i++) {
    currPos[i] = centerPos[i];
    targetPos[i] = currPos[i];
    myServos[i].write(currPos[i]);
    delay(updateDelay);
  }

  // setup light pins
  for(int i=0; i<NUM_MOTORS/2; i++){
    pinMode(lightPins[i],OUTPUT);
    digitalWrite(lightPins[i],LOW);
  }

  // for debugging
  pinMode(13,OUTPUT);
  digitalWrite(13,LOW);

} 


void loop() { 
  // idle state.
  if(currState == STATE_WAIT) {
    if (Serial.available() > 0) {
      // get incoming byte:
      inByte = Serial.read();
      // check for GO signal
      if((inByte == 'G')||(inByte == 'H')) {
        Serial.flush();
        // start dance !!!

        // motor that is gonna do the first writing
        currWriteMotor = 0;

        // turn on the light on first arm
        digitalWrite(lightPins[currWriteMotor/2], HIGH);

        // set targetPos for write arm
        targetPos[currWriteMotor] = writePos[currWriteMotor];
        targetPos[currWriteMotor+1] = writePos[currWriteMotor+1];

        // set targetPos for read arm
        targetPos[currWriteMotor+2] = readPos[currWriteMotor+2];
        targetPos[currWriteMotor+3] = readPos[currWriteMotor+3];

        // new state: go move some motors into read/write positions
        currState = STATE_READ_WRITE;

        // set the fofoque speed
        fofoqueDelay = (inByte == 'G')?DELAY_FAST:DELAY_SLOW;

        // mostly for debugging
        digitalWrite(13,HIGH);
      }
    }
  }
  /////
  // new states !!
  else if(currState == STATE_WRITE) {
    // currWriteMotor points to motor that is writing
    // here it does the actual moving, until it gets to the targetPos

    // when it gets to the targetPos
    if((currPos[currWriteMotor] == targetPos[currWriteMotor])&&(currPos[currWriteMotor+1] == targetPos[currWriteMotor+1])){
      // turn off the light on first arm and send motors back to center position
      digitalWrite(lightPins[currWriteMotor/2], LOW);
      targetPos[currWriteMotor] = centerPos[currWriteMotor];
      targetPos[currWriteMotor+1] = centerPos[currWriteMotor+1];

      // not all writes have a read
      // if this had a read, turn on its light and send it back to centerPos
      if((currWriteMotor+3) < NUM_MOTORS) {
        digitalWrite(lightPins[(currWriteMotor+2)/2], HIGH);
        targetPos[currWriteMotor+2] = centerPos[currWriteMotor+2];
        targetPos[currWriteMotor+3] = centerPos[currWriteMotor+3];
      }
      // go reposition the arms
      currState = STATE_REPOS;
    }
  }
  else if(currState == STATE_REPOS) {
    // currWriteMotor still points to motor that wrote last
    // here it does the actual moving, until it gets to the targetPos

    // at target position
    if((currPos[currWriteMotor] == targetPos[currWriteMotor])&&(currPos[currWriteMotor+1] == targetPos[currWriteMotor+1])){
      // update the current motor
      // now we want to set up read and write positions for the next pair of arms
      currWriteMotor += 2;
      // now currWriteMotor points to motor that is gonna be writing next

      // check if it's a valid motor, or if we are done
      // all writes have a read, only move into write state if there is one to read
      if((currWriteMotor+3) < NUM_MOTORS) {
        // set write positions
        targetPos[currWriteMotor] = writePos[currWriteMotor];
        targetPos[currWriteMotor+1] = writePos[currWriteMotor+1];
        // set read positions
        targetPos[currWriteMotor+2] = readPos[currWriteMotor+2];
        targetPos[currWriteMotor+3] = readPos[currWriteMotor+3]; 
        // if there are arms to move, go move them into read/write positions
        currState = STATE_READ_WRITE;
      }
      // if there are no more motors to read/write
      else{
        // turn light off on last phone
        digitalWrite(lightPins[currWriteMotor/2], LOW);
        // have reset last arm, send STOP message to phone
        Serial.write('S');
        // go wait for next message
        currState = STATE_WAIT;
        // mostly for debugging
        digitalWrite(13,LOW);
      }
    }
  }

  // Fofoqueme states
  else if(currState == STATE_READ_WRITE){
    // currWriteMotor points to motor that is writing
    // here it does the actual moving, until it gets to the targetPos

    // when it gets to the targetPos
    if((currPos[currWriteMotor] == targetPos[currWriteMotor])&&(currPos[currWriteMotor+1] == targetPos[currWriteMotor+1])){
      // send base motors to targetPos+5 for fofoqueme action
      // pretty sure the first one is the base motor
      targetPos[currWriteMotor] = currPos[currWriteMotor]+5;
      //targetPos[currWriteMotor+1] = currPos[currWriteMotor+1]+5;

      // not all writes have a read
      // if this had a read, turn on its light and send base motor to targetPos-5 for fofoqueme action 
      if((currWriteMotor+3) < NUM_MOTORS) {
        digitalWrite(lightPins[(currWriteMotor+2)/2], HIGH);
        // pretty sure the first one is the base motor
        targetPos[currWriteMotor+2] = currPos[currWriteMotor+2]-5;
        //targetPos[currWriteMotor+3] = currPos[currWriteMotor+3]-5;
      }
      // go reposition the arms
      currState = STATE_FOFOQUEME;
    }
  }
  // Fofoqueme states
  else if(currState == STATE_FOFOQUEME){
    // set fofoque speed
    //updateDelay = DELAY_FAST;
    //updateDelay = DELAY_SLOW;
    updateDelay = fofoqueDelay;

    // when it gets to the targetPos
    if((currPos[currWriteMotor] == targetPos[currWriteMotor])&&(currPos[currWriteMotor+1] == targetPos[currWriteMotor+1])){
      // if we already fofoqued 5 times...
      // set targets to go back to center position
      if(fofoquemeCnt > 5){
        // turn off the light on first arm and send motors back to center position
        digitalWrite(lightPins[currWriteMotor/2], LOW);
        targetPos[currWriteMotor] = centerPos[currWriteMotor];
        targetPos[currWriteMotor+1] = centerPos[currWriteMotor+1];

        // not all writes have a read
        // if this had a read, turn on its light and send it back to centerPos
        if((currWriteMotor+3) < NUM_MOTORS) {
          // this might be redundant
          digitalWrite(lightPins[(currWriteMotor+2)/2], HIGH);
          targetPos[currWriteMotor+2] = centerPos[currWriteMotor+2];
          targetPos[currWriteMotor+3] = centerPos[currWriteMotor+3];
        }
        // clear fofoqueme counter
        fofoquemeCnt = 0;
        // reset speed (for quick reposition)
        updateDelay = DELAY_FAST;
        // go reposition the arms
        currState = STATE_REPOS;
      }
      // more fofoquing please
      else{
        // set target to read/write position and increment counter
        // set write positions
        targetPos[currWriteMotor] = writePos[currWriteMotor];
        targetPos[currWriteMotor+1] = writePos[currWriteMotor+1];
        // not all writes have a read
        if((currWriteMotor+3) < NUM_MOTORS) {
          // set read positions
          targetPos[currWriteMotor+2] = readPos[currWriteMotor+2];
          targetPos[currWriteMotor+3] = readPos[currWriteMotor+3];        
        }
        // increment fofoqueme counter
        fofoquemeCnt += 1;
        // if there are arms to move, go move them into read/write positions
        currState = STATE_READ_WRITE;
      }
    }
  }

  // if in a moving state, update currPos and move motors
  if((currState != STATE_WAIT)){
    // if have already waited for move delay, update currPos towards target
    if((millis() - lastTime) > updateDelay) {
      // for all motors, check curr against target
      for(int i=0; i<NUM_MOTORS; i++){
        if(currPos[i] > targetPos[i]){
          currPos[i] -= 1;
        }
        else if(currPos[i] < targetPos[i]){
          currPos[i] += 1;
        }
        // else if (other conditions to achieve a fade...)

        // write the position to the motors
        myServos[i].write(currPos[i]);
      }
      lastTime = millis();
    }
  }

}








