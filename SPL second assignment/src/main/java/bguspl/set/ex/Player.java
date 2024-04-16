package bguspl.set.ex;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread; // check if we have to turn it to puplic

    /**
     * The thread of the AI (computer) player (an additional thread used to generate
     * key presses).
     */
    private Thread aiThread; // check if we have to tue=rn it to puplic

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */

    /////////// WHAT WE ADD ///////////////////////////
    public LinkedBlockingQueue<Boolean> wakeUp;
    private int score;
    private Dealer dealer;
    public boolean inGame;
    public List<Integer> tokens = new LinkedList<>();
    public BlockingQueue<Integer> playerChoose = new LinkedBlockingQueue<>();

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided
     *               manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        inGame = false;
        wakeUp = new LinkedBlockingQueue<>();
        this.tokens = new LinkedList<Integer>();

    }

    /**
     * The main player thread of each player starts here (main loop for the player
     * thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human)
            createArtificialIntelligence();
        while (!terminate) {
            try {
                Integer card = playerChoose.take();
                if (tokens.contains(card)) {
                    tokens.remove(card);
                    table.removeToken(id, card);
                } else {
                    if (tokens.size() < 3) {
                        tokens.add(card);
                        table.placeToken(id, card);
                        if (tokens.size() == 3) {
                            dealer.pc.put(id);
                            Boolean a = wakeUp.take();
                            if (!a) {
                                point();
                            } else {
                                penalty();
                            }
                            wakeUp.clear();
                        }
                    }
                }
            } catch (InterruptedException e) {
            }
        }
        if (!human)
            try {
                aiThread.join();
            } catch (InterruptedException ignored) {
            }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of
     * this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it
     * is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                int rnd = (int) (Math.random() * 12);
                keyPressed(rnd);
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                }

            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();

    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        playerThread.interrupt();
        try {
            playerThread.join();
        } catch (InterruptedException e) {
        }
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (!dealer.canPlace) {
            try {
                playerChoose.put(slot);
            } catch (InterruptedException e) {
            }

        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        if (score > dealer.HighScore) {
            dealer.HighScore = score;
        }
        long time = env.config.pointFreezeMillis;
        for (long i = time; i >= 0; i = i - 500) {
            try {
                if (i < 500)
                    Thread.sleep(i);
                else {
                    Thread.sleep(500);
                    env.ui.setFreeze(id, i);
                }
            } catch (Exception e) {
            }
        }
        env.ui.setFreeze(id, 0);

    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        long time = env.config.penaltyFreezeMillis;
        for (long i = time; i >= 0; i = i - 500) {
            try {
                if (i < 500) {
                    Thread.sleep(i);
                } else {
                    Thread.sleep(500);
                }
                env.ui.setFreeze(id, i);
            } catch (Exception e) {
            }
        }
        env.ui.setFreeze(id, 0);
    }

    public int score() {
        return score;
    }
    ///////////////////////////////////////////////////////

    public void removeAllTokens() {
        for (int i = 0; i < tokens.size(); i++) {
            table.removeToken(this.id, tokens.remove(i));
        }

    }

    public void removeCard(int slot) {
        if (tokens.contains(slot)) {
            inGame = true;
            table.removeToken(this.id, slot);
            tokens.remove(Integer.valueOf(slot));
        }
    }

    public int[] fromSlotsToCards() {
        int[] cards = new int[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) {
            cards[i] = tokens.get(i);

        }
        return cards;
    }

}