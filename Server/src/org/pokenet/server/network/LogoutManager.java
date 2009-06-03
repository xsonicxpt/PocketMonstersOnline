package org.pokenet.server.network;

import java.sql.ResultSet;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.pokenet.server.GameServer;
import org.pokenet.server.backend.entity.Bag;
import org.pokenet.server.backend.entity.PlayerChar;
import org.pokenet.server.battle.Pokemon;

/**
 * Handles logging players out
 * @author shadowkanji
 *
 */
public class LogoutManager implements Runnable {
	private Queue<PlayerChar> m_logoutQueue;
	private Thread m_thread;
	private boolean m_isRunning;
	private MySqlManager m_database;
	
	/**
	 * Default constructor
	 */
	public LogoutManager() {
		m_database = new MySqlManager();
		m_logoutQueue = new ConcurrentLinkedQueue<PlayerChar>();
		m_thread = new Thread(this);
	}
	
	/**
	 * Returns how many players are in the save queue
	 * @return
	 */
	public int getPlayerAmount() {
		return m_logoutQueue.size();
	}
	
	/**
	 * Attempts to logout a player by saving their data. Returns true on success
	 * @param player
	 */
	private boolean attemptLogout(PlayerChar player) {
		if(!m_database.connect(GameServer.getDatabaseHost(), GameServer.getDatabaseUsername(), GameServer.getDatabasePassword()))
			return false;
		m_database.selectDatabase(GameServer.getDatabaseName());
		//TODO: Store all player information
		if(!savePlayer(player))
			return false;
		//Finally, store that the player is logged out and close connection
		m_database.query("UPDATE pn_members SET lastLoginServer='null' WHERE id='" + player.getId() + "'");
		m_database.close();
		//Close the session fully if its not closed already
		if(player.getSession() != null && player.getSession().isConnected())
			player.getSession().close();
		return true;
	}
	
	/**
	 * Returns true if a user is being logged out
	 * This is used during login. If a player is in the logout queue,
	 * the player must wait to be logged out before being logged back in again.
	 * @param username
	 * @return
	 */
	public boolean containsPlayer(String username) {
		Iterator<PlayerChar> it = m_logoutQueue.iterator();
		PlayerChar p;
		while(it.hasNext()) {
			p = it.next();
			if(p.getName().equalsIgnoreCase(username))
				return true;
		}
		return false;
	}
	
	/**
	 * Queues a player to be logged out
	 * @param player
	 */
	public void queuePlayer(PlayerChar player) {
		if(!m_logoutQueue.contains(player) && player != null)
			m_logoutQueue.add(player);
	}

	/**
	 * Called by m_thread.start()
	 */
	public void run() {
		PlayerChar p;
		while(m_isRunning) {
			synchronized(m_logoutQueue) {
				if(m_logoutQueue.peek() != null) {
					p = m_logoutQueue.poll();
					synchronized(p) {
						if(p != null) {
							if(!attemptLogout(p)) {
								m_logoutQueue.add(p);
							} else {
								ConnectionManager.getPlayers().remove(p.getName());
								GameServer.getServiceManager().getMovementService().removePlayer(p.getName());
								GameServer.getInstance().updatePlayerCount();
								System.out.println("INFO: " + p.getName() + " logged out.");
							}
						}
					}
				}
			}
			try {
				Thread.sleep(500);
			} catch (Exception e) {}
		}
	}
	
	/**
	 * Start this logout manager
	 */
	public void start() {
		m_isRunning = true;
		m_thread.start();
	}
	
	/**
	 * Stop this logout manager
	 */
	public void stop() {
		//Stop the thread
		m_isRunning = false;
		System.out.println("INFO: All player data saved successfully.");
	}
	
	/**
	 * Saves a player object to the database (Updates an existing player)
	 * @param p
	 * @return
	 */
	private boolean savePlayer(PlayerChar p) {
		try {
			/*
			 * First, check if they have logged in somewhere else.
			 * This is useful for when as server loses its internet connection
			 */
			ResultSet data = m_database.query("SELECT * FROM pn_members WHERE id='" + p.getId() +  "'");
			data.first();
			if(data.getLong("lastLoginTime") == p.getLastLoginTime()) {
				/*
				 * Update the player row
				 */
				String badges = "";
				for(int i = 0; i < 42; i++) {
					if(p.getBadges()[i] == 1)
						badges = badges + "1";
					else
						badges = badges + "0";
				}
				m_database.query("UPDATE pn_members SET " +
						"sprite='" + p.getSprite() + "', " +
						"money='" + p.getMoney() + "', " +
						"npcMul='" + (String.valueOf(p.getNpcMultiplier()).length() > 20 ?
								String.valueOf(p.getNpcMultiplier()).substring(0, 20) :
									String.valueOf(p.getNpcMultiplier())) + "', " +
						"skHerb='" + p.getHerbalismExp() + "', " +
						"skCraft='" + p.getCraftingExp() + "', " +
						"skFish='" + p.getFishingExp() + "', " +
						"skTrain='" + p.getTrainingExp() + "', " +
						"skCoord='" + p.getCoordinatingExp() + "', " +
						"skBreed='" + p.getBreedingExp() + "', " +
						"x='" + p.getX() + "', " +
						"y='" + p.getY() + "', " +
						"mapX='" + p.getMapX() + "', " +
						"mapY='" + p.getMapY() + "', " +
						"healX='" + p.getHealX() + "', " +
						"healY='" + p.getHealY() + "', " +
						"healMapX='" + p.getHealMapX() + "', " +
						"healMapY='" + p.getHealMapY() + "', " +
						"isSurfing='" + String.valueOf(p.isSurfing()) + "', " +
						"badges='" + badges + "' " +
						"WHERE username='" + p.getName() + "' AND id='" + p.getId() + "'");
				/*
				 * Second, update the party
				 */
				//Save all the Pokemon
				for(int i = 0; i < 6; i++) {
					if(p.getParty() != null && p.getParty()[i] != null) {
						if(p.getParty()[i].getDatabaseID() == -1) {
							//This is a new Pokemon, add it to the database
							if(!saveNewPokemon(p.getParty()[i], m_database))
								return false;
						} else {
							//Old Pokemon, just update
							if(!savePokemon(p.getParty()[i]))
								return false;
						}
					}
				}
				//Save all the Pokemon id's in the player's party
				if(p.getParty() != null) {
					m_database.query("UPDATE pn_party SET " +
							"pokemon0='" + (p.getParty()[0] != null ? p.getParty()[0].getDatabaseID() : -1) + "', " +
							"pokemon1='" + (p.getParty()[1] != null ? p.getParty()[1].getDatabaseID() : -1) + "', " +
							"pokemon2='" + (p.getParty()[2] != null ? p.getParty()[2].getDatabaseID() : -1) + "', " +
							"pokemon3='" + (p.getParty()[3] != null ? p.getParty()[3].getDatabaseID() : -1) + "', " +
							"pokemon4='" + (p.getParty()[4] != null ? p.getParty()[4].getDatabaseID() : -1) + "', " +
							"pokemon5='" + (p.getParty()[5] != null ? p.getParty()[5].getDatabaseID() : -1) + "' " +
							"WHERE member='" + p.getId() + "'");
				} else
					return true;
				/*
				 * Save the player's bag
				 */
				if(p.getBag() == null || !saveBag(p.getBag()))
					return false;
				/*
				 * Finally, update all the boxes
				 */
				if(p.getBoxes() != null) {
					for(int i = 0; i < 9; i++) {
						if(p.getBoxes()[i] != null) {
							if(p.getBoxes()[i].getDatabaseId() == -1) {
								//New box
								m_database.query("INSERT INTO pn_box(member, pokemon0, pokemon1, pokemon2, " +
										"pokemon3, pokemon4, pokemon5, pokemon 6, pokemon7, pokemon8, pokemon9, " +
										"pokemon10, pokemon11, pokemon12, pokemon13, pokemon14, pokemon15, pokemon16, " +
										"pokemon17, pokemon18, pokemon19, pokemon20, pokemon21, pokemon22, pokemon23, " +
										"pokemon24, pokemon25, pokemon26, pokemon27, pokemon28, pokemon29, pokemon30) " +
										"VALUES ('" + p.getId() + "'," +
										"'-1','-1','-1','-1','-1'," +
										"'-1','-1','-1','-1','-1'," +
										"'-1','-1','-1','-1','-1'," +
										"'-1','-1','-1','-1','-1'," +
										"'-1','-1','-1','-1','-1'," +
										"'-1','-1','-1','-1','-1')");
								ResultSet result = m_database.query("SELECT * FROM pn_box WHERE member='" + p.getId() + "'");
								result.last();
								p.getBoxes()[i].setDatabaseId(result.getInt("id"));
							}
							//Save all pokemon first
							for(int j = 0; j < p.getBoxes()[i].getPokemon().length; j++) {
								if(p.getBoxes()[i].getPokemon(j).getId() == -1) {
									if(!saveNewPokemon(p.getBoxes()[i].getPokemon(j), m_database))
										return false;
								} else {
									if(!savePokemon(p.getBoxes()[i].getPokemon(j)))
										return false;
								}
							}
							//Now save all references to the box
							for(int j = 0; j < p.getBoxes()[i].getPokemon().length; j++) {
								m_database.query("UPDATE pn_box SET pokemon" + j + "='" +  p.getBoxes()[i].getPokemon(j).getDatabaseID() + "' " +
										"WHERE id='" + p.getBoxes()[i].getDatabaseId() + "'");
							}
						}
					}
				}
				//Dispose of the player object
				if(p.getMap() != null)
					p.getMap().removeChar(p);
				p.dispose();
				return true;
			} else
				return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}
	
	/**
	 * Saves a pokemon to the database that didn't exist in it before
	 * @param p
	 */
	private boolean saveNewPokemon(Pokemon p, MySqlManager db) {
		try {
			/*
			 * Insert the Pokemon into the database
			 */
			db.query("INSERT INTO pn_pokemon" +
					"(name, speciesName, exp, baseExp, expType, isFainted, level, happiness, " +
					"gender, nature, abilityName, itemName, isShiny, originalTrainerName, date)" +
					"VALUES (" +
					"'" + MySqlManager.parseSQL(p.getName()) +"', " +
					"'" + MySqlManager.parseSQL(p.getSpeciesName()) +"', " +
					"'" + String.valueOf(p.getExp()) +"', " +
					"'" + p.getBaseExp() +"', " +
					"'" + MySqlManager.parseSQL(p.getExpType().name()) +"', " +
					"'" + String.valueOf(p.isFainted()) +"', " +
					"'" + p.getLevel() +"', " +
					"'" + p.getHappiness() +"', " +
					"'" + p.getGender() +"', " +
					"'" + MySqlManager.parseSQL(p.getNature().getName()) +"', " +
					"'" + MySqlManager.parseSQL(p.getAbilityName()) +"', " +
					"'" + MySqlManager.parseSQL(p.getItemName()) +"', " +
					"'" + String.valueOf(p.isShiny()) +"', " +
					"'" + MySqlManager.parseSQL(p.getOriginalTrainer()) + "', " +
					"'" + MySqlManager.parseSQL(p.getDateCaught()) + "')");
			/*
			 * Get the pokemon's database id and attach it to the pokemon.
			 * This needs to be done so it can be attached to the player in the database later.
			 */
			ResultSet result = db.query("SELECT * FROM pn_pokemon WHERE originalTrainerName='"  + MySqlManager.parseSQL(p.getOriginalTrainer()) + 
					"' AND date='" + MySqlManager.parseSQL(p.getDateCaught()) + "'");
			result.first();
			p.setDatabaseID(result.getInt("id"));
			db.query("UPDATE pn_pokemon SET move0='" + MySqlManager.parseSQL(p.getMove(0).getName()) +
					"', move1='" + (p.getMove(1) == null ? "null" : MySqlManager.parseSQL(p.getMove(1).getName())) +
					"', move2='" + (p.getMove(2) == null ? "null" : MySqlManager.parseSQL(p.getMove(2).getName())) +
					"', move3='" + (p.getMove(3) == null ? "null" : MySqlManager.parseSQL(p.getMove(3).getName())) +
					"', hp='" + p.getHealth() +
					"', atk='" + p.getStat(1) +
					"', def='" + p.getStat(2) +
					"', speed='" + p.getStat(3) +
					"', spATK='" + p.getStat(4) +
					"', spDEF='" + p.getStat(5) +
					"', evHP='" + p.getEv(0) +
					"', evATK='" + p.getEv(1) +
					"', evDEF='" + p.getEv(2) +
					"', evSPD='" + p.getEv(3) +
					"', evSPATK='" + p.getEv(4) +
					"', evSPDEF='" + p.getEv(5) +
					"' WHERE id='" + p.getDatabaseID() + "'");
			db.query("UPDATE pn_pokemon SET ivHP='" + p.getIv(0) +
					"', ivATK='" + p.getIv(1) +
					"', ivDEF='" + p.getIv(2) +
					"', ivSPD='" + p.getIv(3) +
					"', ivSPATK='" + p.getIv(4) +
					"', ivSPDEF='" + p.getIv(5) +
					"', pp0='" + p.getPp(0) +
					"', pp1='" + p.getPp(1) +
					"', pp2='" + p.getPp(2) +
					"', pp3='" + p.getPp(3) +
					"', maxpp0='" + p.getMaxPp(0) +
					"', maxpp1='" + p.getMaxPp(1) +
					"', maxpp2='" + p.getMaxPp(2) +
					"', maxpp3='" + p.getMaxPp(3) +
					"', ppUp0='" + p.getPpUpCount(0) +
					"', ppUp1='" + p.getPpUpCount(1) +
					"', ppUp2='" + p.getPpUpCount(2) +
					"', ppUp3='" + p.getPpUpCount(3) +
					"' WHERE id='" + p.getDatabaseID() + "'");
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}
	
	/**
	 * Updates a pokemon in the database
	 * @param p
	 */
	private boolean savePokemon(Pokemon p) {
		try {
			/*
			 * Update the pokemon in the database
			 */
			m_database.query("UPDATE pn_pokemon SET " +
					"name='" + MySqlManager.parseSQL(p.getName()) +"', " +
					"speciesName='" + MySqlManager.parseSQL(p.getSpeciesName()) +"', " +
					"exp='" + String.valueOf(p.getExp()) +"', " +
					"baseExp='" + p.getBaseExp() +"', " +
					"expType='" + MySqlManager.parseSQL(p.getExpType().name()) +"', " +
					"isFainted='" + String.valueOf(p.isFainted()) +"', " +
					"level='" + p.getLevel() +"', " +
					"happiness='" + p.getHappiness() +"', " +
					"gender='" + p.getGender() +"', " +
					"nature='" + MySqlManager.parseSQL(p.getNature().getName()) +"', " +
					"abilityName='" + MySqlManager.parseSQL(p.getAbilityName()) +"', " +
					"itemName='" + MySqlManager.parseSQL(p.getItemName()) +"', " +
					"isShiny='" + String.valueOf(p.isShiny()) +"' " +
					"WHERE id='" + p.getDatabaseID() + "'");
			m_database.query("UPDATE pn_pokemon SET move0='" + MySqlManager.parseSQL(p.getMove(0).getName()) +
					"', move1='" + (p.getMove(1) == null ? "null" : MySqlManager.parseSQL(p.getMove(1).getName())) +
					"', move2='" + (p.getMove(2) == null ? "null" : MySqlManager.parseSQL(p.getMove(2).getName())) +
					"', move3='" + (p.getMove(3) == null ? "null" : MySqlManager.parseSQL(p.getMove(3).getName())) +
					"', hp='" + p.getHealth() +
					"', atk='" + p.getStat(1) +
					"', def='" + p.getStat(2) +
					"', speed='" + p.getStat(3) +
					"', spATK='" + p.getStat(4) +
					"', spDEF='" + p.getStat(5) +
					"', evHP='" + p.getEv(0) +
					"', evATK='" + p.getEv(1) +
					"', evDEF='" + p.getEv(2) +
					"', evSPD='" + p.getEv(3) +
					"', evSPATK='" + p.getEv(4) +
					"', evSPDEF='" + p.getEv(5) +
					"' WHERE id='" + p.getDatabaseID() + "'");
			m_database.query("UPDATE pn_pokemon SET ivHP='" + p.getIv(0) +
					"', ivATK='" + p.getIv(1) +
					"', ivDEF='" + p.getIv(2) +
					"', ivSPD='" + p.getIv(3) +
					"', ivSPATK='" + p.getIv(4) +
					"', ivSPDEF='" + p.getIv(5) +
					"', pp0='" + p.getPp(0) +
					"', pp1='" + p.getPp(1) +
					"', pp2='" + p.getPp(2) +
					"', pp3='" + p.getPp(3) +
					"', maxpp0='" + p.getMaxPp(0) +
					"', maxpp1='" + p.getMaxPp(1) +
					"', maxpp2='" + p.getMaxPp(2) +
					"', maxpp3='" + p.getMaxPp(3) +
					"', ppUp0='" + p.getPpUpCount(0) +
					"', ppUp1='" + p.getPpUpCount(1) +
					"', ppUp2='" + p.getPpUpCount(2) +
					"', ppUp3='" + p.getPpUpCount(3) +
					"' WHERE id='" + p.getDatabaseID() + "'");
			return true;
		} catch (Exception e) {
			return false;
		}
	}
	
	/**
	 * Saves a bag to the database.
	 * @param b
	 * @return
	 */
	private boolean saveBag(Bag b) {
		try {
			for(int i = 0; i < b.getItems().length; i++) {
				if(b.getItems()[i] != null) {
					/*
					 * NOTE: Items are stored as values 1 - 999
					 */
					m_database.query("UPDATE pn_bag SET item" + i + "='" 
							+ (b.getItems()[i].getItemNumber() > 0 ? b.getItems()[i].getItemNumber() : 0) +
							", quantity" + i + "='" +
							(b.getItems()[i].getQuantity() > 0 ? b.getItems()[i].getQuantity() : 0) +
							"' WHERE id='" + b.getDatabaseId() + "'");
				}
			}
			return true;
		} catch (Exception e) {
			return false;
		}
	}

}