/*
 * Copyright 2018-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.stitch.rover;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

// Base Stitch Packages
import com.google.android.things.pio.PeripheralManager;
import com.mongodb.stitch.android.core.Stitch;
import com.mongodb.stitch.android.core.StitchAppClient;
import com.mongodb.stitch.core.auth.providers.serverapikey.ServerApiKeyCredential;
import com.mongodb.stitch.core.internal.common.BsonUtils;

// Packages needed to interact with MongoDB and Stitch
import com.mongodb.stitch.android.services.mongodb.remote.RemoteMongoClient;
import com.mongodb.stitch.android.services.mongodb.remote.RemoteMongoCollection;
import com.mongodb.stitch.core.services.mongodb.remote.sync.ConflictHandler;
import com.mongodb.stitch.core.services.mongodb.remote.sync.internal.ChangeEvent;
import com.mongodb.stitch_car_test.R;

//General Document Classes
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.types.ObjectId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class RoverActivity extends Activity implements ConflictHandler<Rover> {
    private static final String TAG = "RoverActivity";

    private RemoteMongoCollection<Rover> rovers;
    private String userId;

    public FrontWheels frontWheels;
    public BackWheels backWheels;

    public Sensor TempSensor;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        final StitchAppClient client = Stitch.getDefaultAppClient();
        final RemoteMongoClient mongoClient =
            client.getServiceClient(RemoteMongoClient.factory, "mongodb-atlas");
        rovers = mongoClient.getDatabase(Rover.ROVERS_DATABASE)
            .getCollection(Rover.ROVERS_COLLECTION, Rover.class)
            .withCodecRegistry(CodecRegistries.fromRegistries(
                BsonUtils.DEFAULT_CODEC_REGISTRY,
                CodecRegistries.fromCodecs(Rover.codec)));

        rovers.sync().configure(
            this,
            null,
            (documentId, error) -> Log.e(TAG, error.getLocalizedMessage()));

        try {
            this.TempSensor = new Sensor(12);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        for(int i = 0; i < 20; i ++){
            try {
                Log.d(TAG,"The temperature is " + TempSensor.getI2CReading());
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        try {
            this.frontWheels = new FrontWheels("I2C1", 0);
            this.backWheels = new BackWheels();
            BackWheels.test();
            FrontWheels.test(0);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        doLogin();
    }

    private void doLogin() {
        Stitch.getDefaultAppClient().getAuth().loginWithCredential(
            new ServerApiKeyCredential(getString(R.string.stitch_rover_api_key)))
            .addOnSuccessListener(user -> {
                userId = user.getId();
                invalidateOptionsMenu();
                Toast.makeText(RoverActivity.this, "Logged in", Toast.LENGTH_SHORT).show();

                if (rovers.sync().getSyncedIds().isEmpty()) {
                    rovers.sync().insertOne(new Rover(userId));
                }

                moveLoop();
            })
            .addOnFailureListener(e -> {
                invalidateOptionsMenu();
                Log.d(TAG, "error logging in", e);
                Toast.makeText(RoverActivity.this, "Failed logging in", Toast.LENGTH_SHORT).show();
            });
    }

    private Document getRoverFilter() {
        return new Document("_id", userId);
    }

    private Document getLatestMoveFilter() {
        return getRoverFilter()
            .append("moves",
                new Document("$exists", true)
                    .append("$not", new Document("$size", 0)));
    }

    private void moveLoop() {
        rovers.sync().find(getLatestMoveFilter()).first().addOnSuccessListener(rover -> {
            if (rover == null) {
                try {
                    Thread.sleep(1000);

                    try {
                        if(backWheels.getSpeed() == 0){
                            backWheels.stop();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                moveLoop();
            } else {
                final Move move = rover.getMoves().get(0);
                doMove(move);
                final Document update = new Document("$pull", new Document("moves",
                    new Document("_id", move.getId())));
                rovers.sync().updateOne(getRoverFilter(), update).addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        Log.d(TAG, "failed to update rover document", task.getException());
                    }
                    moveLoop();
                });
            }
        }).addOnFailureListener(e -> Log.d(TAG, "failed to find rover document", e));
    }

    private void doMove(final Move move) {
        Log.i(TAG, "Doing move " + move);
        Toast.makeText(RoverActivity.this, "Doing move " + move, Toast.LENGTH_SHORT).show();
        int speed = move.getSpeed();
        int DELAY = 10;
        int MOVE_LENGTH = 3000;

        try {
            frontWheels.turn(move.getAngle());

            if(speed > 1){
                backWheels.forward();
            } else {
                backWheels.backward();
            }

            for(int i = backWheels.getSpeed(); i < 8*Math.abs(speed); i++){
                backWheels.setSpeed(i);
                Thread.sleep(DELAY);
            }

            Thread.sleep(MOVE_LENGTH);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public Rover resolveConflict(
        final BsonValue documentId,
        final ChangeEvent<Rover> localEvent,
        final ChangeEvent<Rover> remoteEvent
    ) {
        if (localEvent.getFullDocument().getLastMoveCompleted() == null) {
            return remoteEvent.getFullDocument();
        }
        // Given this sync model consists of a single producer and a single consumer, a conflict
        // can only occur when a production and consumption happens at the same "time". That means
        // that there should always be an overlap of moves during a conflict and that the last
        // move completed is always present in the remote. Therefore we should trim all moves up to
        // and including the last completed move.
        final Rover localRover = localEvent.getFullDocument();
        final String lastMoveCompleted = localRover.getLastMoveCompleted();
        final Rover remoteRover = remoteEvent.getFullDocument();
        final List<Move> nextMoves = new ArrayList<>(remoteRover.getMoves().size());
        boolean caughtUp = false;
        for (final Move move : remoteRover.getMoves()) {
            if (move.getId().equals(lastMoveCompleted)) {
                caughtUp = true;
            }
            if (caughtUp) {
                nextMoves.add(move);
            }
        }
        return new Rover(localRover, nextMoves);
    }
}
