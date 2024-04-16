package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;
    ///////////// what we add ///////////
    boolean canPlace = true;
    private Integer playerId;
    public volatile int HighScore;
    public BlockingQueue<Integer> pc;
    Object locker = new Object();
    Thread[] playersThreads;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        this.pc = new LinkedBlockingQueue<>();
        playersThreads = new Thread[players.length];
        terminate = false;
        this.HighScore = 0;

    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        for (Player pl : players) {
            Thread playerThread = new Thread(pl);
            playersThreads[pl.id] = playerThread;
            playersThreads[pl.id].start();
        }
        while (!shouldFinish()) {
            updateTimerDisplay(true);
            placeCardsOnTable();
            updateTimerDisplay(true);
            canPlace = false;
            timerLoop();
            canPlace = true;
            if (deck.size() == 0 && env.util.findSets(toList(table.cardsOT), 1).size() == 0
                    || toList(table.cardsOT).size() == 0 && env.util.findSets(deck, 1).size() == 0)
                break;
            updateTimerDisplay(true);
            removeAllCardsFromTable();

        }
        removeAllCardsFromTable();
        terminate();
        announceWinners();

        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did
     * not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            if (env.util.findSets(toList(table.cardsOT), 1).size() == 0 && deck.size() == 0)
                break;
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        for (int i = players.length - 1; i >= 0; i--) {
            players[i].terminate();
        }

    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(allCardsOnT(), 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        try {
            if (playerId != null) {
                int[] slots = players[playerId].fromSlotsToCards();
                int[] cards = new int[slots.length];
                for (int i = 0; i < slots.length; i++) {
                    if (table.slotToCard[slots[i]] == null) {
                        players[playerId].wakeUp.put(true);
                        players[playerId].tokens.remove((Integer) slots[i]);
                        break;
                    }
                    cards[i] = table.slotToCard[slots[i]];
                }
                if (cards.length < env.config.featureSize) {
                    players[playerId].wakeUp.put(true);
                } else if (env.util.testSet(cards) == false) {
                    players[playerId].wakeUp.put(true);
                } else {
                    for (Player p : players) {
                        for (int i = 0; i < cards.length; i++) {
                            p.tokens.remove((Integer) slots[i]);
                            table.removeToken(p.id, slots[i]);
                        }
                    }
                    players[playerId].wakeUp.put(false);
                    // here remove card
                    for (int j = 0; j < cards.length; j++) {
                        table.removeCard(slots[j]);
                    }
                    updateTimerDisplay(true);
                }
            }
        } catch (InterruptedException e) {
        }

    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        synchronized (locker) {
            shuffleDeck();
            for (int i = 0; i < 12; i++) {
                Integer card = table.slotToCard[i]; // Get a card if there is any on i'th place.
                if (card == null) {
                    if (deck.size() > 0 && deck.get(0) != null) {
                        card = deck.remove(0);
                        table.placeCard(card, i);
                    }
                }
            }
        }
    }

    private void sleepUntilWokenOrTimeout() {
        try {
            if (reshuffleTime - System.currentTimeMillis() > env.config.turnTimeoutWarningMillis) {
                playerId = pc.poll(1000, TimeUnit.MILLISECONDS);
            } else {
                playerId = pc.poll(10, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
        }
    }

    private void updateTimerDisplay(boolean reset) {
        if (reset) {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
        } else {
            long timeLeft = reshuffleTime - System.currentTimeMillis();
            env.ui.setCountdown(timeLeft, timeLeft < env.config.turnTimeoutWarningMillis);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        for (int i = 0; i < 12; i++) {
            deck.add(table.slotToCard[i]);
            table.removeCard(i);
        }
        shuffleDeck();
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        LinkedList<Integer> temp = new LinkedList<>();
        for (Player pl : players) {
            if (pl.score() == HighScore)
                temp.add(pl.id);
        }
        int[] winners = new int[temp.size()];
        for (int i = 0; i < temp.size(); i++) {
            winners[i] = temp.get(i);
        }
        env.ui.announceWinner(winners);
    }

    public void checkLegalSets(int[] palyerCards, int id) {
        if (palyerCards.length == 3) {
            try {
                if (!env.util.testSet(palyerCards)) {
                    players[playerId].wakeUp.put(true);
                } else {
                    table.removeToken(playerId, table.cardToSlot[palyerCards[0]]);
                    table.removeToken(playerId, table.cardToSlot[palyerCards[1]]);
                    table.removeToken(playerId, table.cardToSlot[palyerCards[2]]);
                    players[playerId].tokens.clear();
                    removeCardsFromTable(palyerCards);
                    updateTimerDisplay(true);
                    players[playerId].wakeUp.put(false);
                }
            } catch (Exception e) {
            }
        }

    }

    public List<Integer> toList(Integer[] cards) {
        List<Integer> list = new LinkedList<>();
        for (int i = 0; i < cards.length; i++) {
            if (cards[i] != null) {
                list.add(cards[i]);
            }
        }
        return list;

    }

    public List<Integer> allCardsOnT() {
        List<Integer> list = new LinkedList<>();
        for (int i = 0; i < deck.size(); i++) {
            list.add(deck.get(i));
        }
        for (int i = 0; i < toList(table.cardsOT).size(); i++) {
            list.add(toList(table.cardsOT).get(i));
        }
        return list;
    }

    private void removeCardsFromTable(int[] chosenSet) {
        int slot1 = table.cardToSlot[chosenSet[0]];
        int slot2 = table.cardToSlot[chosenSet[1]];
        int slot3 = table.cardToSlot[chosenSet[2]];
        table.removeCard(table.cardToSlot[chosenSet[0]]);
        table.placeCard(deck.remove(0), slot1);
        table.removeCard(table.cardToSlot[chosenSet[1]]);
        table.placeCard(deck.remove(0), slot2);
        table.removeCard(table.cardToSlot[chosenSet[2]]);
        table.placeCard(deck.remove(0), slot3);
    }

    private void shuffleDeck() {
        Collections.shuffle(deck);
    }

}