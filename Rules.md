# Rules

Source of truth for game logic. The app implements sections 1, 6, 7 and 8. Sections 2 to 5 are played on the table; the app does not check them.

## 1. Players and cards
- Game has 2 or 3 players.
- One standard 52-card deck, 4 suits, no jokers.
- 2 players: 13 cards each.
- 3 players: 7 cards each.
- Cards not dealt form the **closed deck** (face down).
- After dealing, 1 card from the closed deck is turned face up. It starts the **open deck** (face up).
- Discarded cards go onto the open deck.

## 2. Turn order
- Play is turn based.
- Each turn has three steps, in this order: **draw, meld (optional), discard**.

## 3. Draw (mandatory, at least 1 card)
- Option A: take 1 card from the closed deck.
- Option B: take 1 or more cards from the open deck.
- If more than 1 card is taken from the open deck, the last card taken must be melded in the same turn. (The app does not check this.)

## 4. Meld (optional)
- A **run** is 3 or more cards of the same suit in consecutive rank. Example: 3-4-5 or 6-7-8 of one suit.
- A **set** is 3 or 4 cards of the same rank, each of a different suit.
- A new meld has 3 or more cards.
- A player may also add a card from hand to a run or set already on the table.
- Ace is low in A-2-3 and high in Q-K-A. No wrap-around such as K-A-2.

## 5. Discard and going out
- Each turn ends with exactly 1 discard to the open deck.
- If that discard is the player's last card, the player goes out and the round ends.
- The player who goes out has 0 cards left in hand.

## 6. Card values
| Card | Points |
|---|---|
| 2 to 10 | Face value |
| J, Q, K | 10 |
| A in A-2-3 | 1 |
| A anywhere else | 10 |

## 7. Scoring (live)
- **Melded points are scored live.** The moment a player lays a meld, or adds a card to a meld, the value of those cards is added to that player's score.
- **Hand points are deducted at the end of the round.** They are the sum of values of the cards still in a player's hand when the round ends.
- **Round score = melded points − hand points.** It can be negative.
- Every player scores, not only the player who goes out. The player who goes out has hand points 0.
- No bonus for going out. No penalty for a player who laid no meld.
- Round scores add up into a running total for each player.
- Live total = total of finished rounds + melded points so far in the current round.

## 8. Game end
- Before the game, the players choose a **target score**. Examples: 100, 200.
- A live total may pass the target during a round. The round is still played to the end.
- When the round ends, check the totals. If any total is at or above the target, the game is over.
- The player with the highest total wins.
- If two or more players tie for highest, play another round until one player is alone in front.

## How the app uses these rules
- Setup: choose 2 or 3 players and a target score. The app shows the cards to deal (13 or 7).
- During a round:
  - Pick the player, tap the cards of the meld (2 to 10, J/Q/K = 10, A = 1 or A = 10), then add the meld.
  - The meld points go to that player's live total straight away, and appear on the TV.
- End of round:
  - Enter each player's hand points and who went out. The app deducts hand points and starts a new round.
  - The app then checks the target and decides the winner.
- The phone can undo the last meld, reopen the last round, or edit any finished round.
