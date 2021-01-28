package CS4960;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import org.lemurproject.galago.core.retrieval.Retrieval;
import org.lemurproject.galago.core.retrieval.RetrievalFactory;
import org.lemurproject.galago.core.retrieval.query.Node;
import org.lemurproject.galago.core.retrieval.query.StructuredQuery;
import org.lemurproject.galago.utility.Parameters;
//import org.lemurproject.galago.core.retrieval.RetrievalFactory;
import org.lemurproject.galago.core.retrieval.ScoredDocument;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Set;


/**
 * This is a project for CS 4960 introduction to information retrieval
 * Fall 2020, University of Utah, Professor Qingyao AI
 * 
 * The goal of this project was to gain insight on information retrieval
 * in a practical context through the construction of a serach engine with a
 * easily intractable GUI over an amazon dataset. This project allows users to
 * input a query to search for products from the musical instruments category
 * from Amazon, and specify whether to sort retrieved results based off relevance
 * or by rating
 * 
 * Some Notes: The inverted index used in the project was constructed using
 *			   galago's tools
 *
 *			   The exact products retrieved may differ based off user specification
 *             of how they should be sorted; this was intentional, due to more accurately
 *             simulating a real world problem, and therefore required the implementation
 *             of a balancing system to ensure a preservation of general relevance
 *             
 *             Each retrieved result also contains some information that will likely be 
 *             pertinent to an end user, such as product name, average star rating, a 
 *             product image if applicable, and positive and negative keywords derived from
 *             reviews associated with the product.
 * 
 * @author Brian Dong
 *
 */
public class Search implements ActionListener {

	// Swing objects used for UI
	
	// Main frame everything else is placed in
	private JFrame frame;

	// Panels used to contain the four pages used in this search engine
	private JPanel homePage, resultsPage, inputToggle, resultsText;

	// UI Interactables
	private JLabel prompt, resultsForLabel;
	private JButton searchButton, backButton;
	private JToggleButton relevanceOp, rankingOp;
	private JTextField queryInput;
	private ButtonGroup searchOptions;

	// Parameters used for galago retreival
	private String jsonConfigFile;
	private Parameters globalParams;
	private String pathIndexBase;
	private Retrieval retrieval;

	// Data structures used to store loaded data for faster retrieval
	private HashMap<String, ArrayList<JSONObject>> reviewMap;
	private HashMap<String, Double> reviewAvg;
	private HashMap<String, JSONObject> metaMap;

	private DecimalFormat doubleFormat;

	/**
	 * Constructor that creates the search object, which loads in the data 
	 * and initializes the UI
	 * @throws Exception
	 */
	public Search() throws Exception {
		frame = new JFrame();
		// Reads in data from file
		loadData();
		
		// Initializes GUI panels
		initializeHome();
		initializeResults();
		doubleFormat = new DecimalFormat("#.##");

		// Sets other needed GUI options and then draws
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setTitle("CS4960 Project");
		frame.pack();
		frame.setVisible(true);
		drawHome();
	}

	/**
	 * Takes in a search query and returns up to 10 results from that query from
	 * Galago sorted by relevance
	 * 
	 * @param query, the search query provided by the user
	 * @return List<String> returnList, List with 10 results derived from the query utilizing
	 * 								    Galago retrieval
	 * @throws Exception
	 */
	public List<String> relevanceSearch(String query) throws Exception {
		// Utilizes galago retrieval to get a list of products sorted by relevance 
		Set<String> acins = runQuery(globalParams, "1", query, retrieval, "postings.krovetz", "jm", pathIndexBase, 10)
				.keySet();
		
		// Initializes the list used to return the retrieved products
		ArrayList<String> returnList = new ArrayList<String>();
		
		// Populates the return list with retrieved products, sorted by relevance
		for (String s : acins) {
			// Adds product name
			returnList.add((String) metaMap.get(s).get("title"));
			// Adds image link if exists
			if (((JSONArray) metaMap.get(s).get("image")).size() > 0)
				returnList.add((String) ((JSONArray) metaMap.get(s).get("image")).get(0));
			else
				returnList.add("No Image");
			// Adds additional product data
			returnList.add("Avg Rating: " + doubleFormat.format(reviewAvg.get(s)) + " " + extractKeywords(s));
		}
		return returnList;
	}

	/**
	 * Takes in a search query and returns up to 10 results from that query from
	 * Galago sorted by ranking Utilizes a balancing scheme
	 * 
	 * Note: The balancing scheme functions by sorting the list retrieved using galago
	 * by rating and then populating a list up to 10 slots in descending rating order,
	 * by inserting a product into the return list if it's relevance score is "close" to
	 * the product that would populate that spot's relevance score when sorting by rating
	 * Goal is to get average error below 10%, which may be done over several iterations,
	 * each one reducing the acceptable threshold to reduce the average error
	 * 
	 * @param query, the search query provided by the user
	 * @return List<String> returnList, List with 10 results derived from the query utilizing
	 * 								    Galago retrieval
	 * @throws Exception
	 */
	public ArrayList<String> rankingSearch(String query) throws Exception {
		// Retrieves the top 100 relevant items for a query
		LinkedHashMap<String, Double> top100 = runQuery(globalParams, "1", query, retrieval, "postings.krovetz", "jm",
				pathIndexBase, 100);
		// Gets the acins for the top 100 relevant items
		ArrayList<String> acins = new ArrayList<String>(top100.keySet());

		// Retrieves the average review ratings for the 100 retrieved items
		HashMap<Double, ArrayList<String>> ratingsMap = new HashMap<Double, ArrayList<String>>();
		for (String s : acins) {
			if (!ratingsMap.containsKey(reviewAvg.get(s)))
				ratingsMap.put(reviewAvg.get(s), new ArrayList<String>());
			ratingsMap.get(reviewAvg.get(s)).add(s);
		}

		// Sorts by average rating
		ArrayList<Double> averages = new ArrayList<Double>(ratingsMap.keySet());
		Collections.sort(averages);

		// Variables used to report average error from sorting by relevance
		double avgError = 0;
		double totalRelevance = 0;
		
		// Threshold used in balancing
		double threshold = 1.1;

		// Populates the return list with retrieved products, sorted by rating
		ArrayList<String> returnList = new ArrayList<String>();
		// Loop to iterate over average ratings
		for (int i = averages.size() - 1; i > -1; i--) {
			// Loops over each product with a given average rating
			for (int j = 0; j < ratingsMap.get(averages.get(i)).size(); j++) {

				// Adds product to list if it is within the acceptable threshold
				if (top100.get(ratingsMap.get(averages.get(i)).get(j))
						/ top100.get(acins.get(returnList.size() / 3)) < threshold) {
					avgError += top100.get(ratingsMap.get(averages.get(i)).get(j))
							- top100.get(acins.get(returnList.size() / 3));
					totalRelevance += top100.get(acins.get(returnList.size() / 3));

					// Adds product name
					returnList.add((String) metaMap.get(ratingsMap.get(averages.get(i)).get(j)).get("title"));
					// Adds product image if it exists
					if (((JSONArray) metaMap.get(ratingsMap.get(averages.get(i)).get(j)).get("image")).size() > 0)
						returnList.add(
								(String) ((JSONArray) metaMap.get(ratingsMap.get(averages.get(i)).get(j)).get("image"))
										.get(0));
					else
						returnList.add("No Image");
					// Adds additional product data
					returnList.add(
							"Avg Rating: " + doubleFormat.format(reviewAvg.get(ratingsMap.get(averages.get(i)).get(j)))
									+ " " + extractKeywords(ratingsMap.get(averages.get(i)).get(j)));
					if (returnList.size() >= 60)
						break;
				}
			}
			// Breaks the loop once populated
			if (returnList.size() >= 60) {
				// Checks to see if average error is acceptable
				if ((avgError / 10.0) / (totalRelevance / 10.0) < 0.05)
					break;
				// Reruns population process if error is too high, with a lower threshold
				else {
					threshold += -0.005;
					returnList.clear();
					avgError = 0;
					totalRelevance = 0;
					i = averages.size() - 1;
				}
			}
		}

		// Reports average error -- used for analysis purposes
		System.out.println("Ultimate Threshold Used: " + threshold);
		System.out.println("Average Error: " + avgError / 10.0);
		System.out.println("Total Relevance: " + totalRelevance / 10.0);
		System.out.println("Average % Change: " + (avgError / 10.0) / (totalRelevance / 10.0));
		return returnList;
	}

	/**
	 * Retrieves positive and negative keywords for a given product's asin
	 * if possible
	 * 
	 * Note: keywords are derived from a the user provided summaries of their reviews
	 * Positive keywords are derived from 4-5 star reviews, negative from 1-2 star reviews
	 * Returned string may be empty, or return only positive or negative reviews, this is 
	 * expected behavior if there are not enough reviews to draw from
	 * 
	 * @param String asin: the product to extract keywords for
	 * @return String keywords: extracted keywords
	 */
	private String extractKeywords(String asin) {
		// Initializes strings for storage
		String positive = "";
		String negative = "";
		
		// Gets JSONObject of a given product from asin, then iterates through its reviews
		for (JSONObject obj : reviewMap.get(asin)) {
			// Extract keywords if review is positive
			if (positive.compareTo("") == 0 && (Double) obj.get("overall") >= 4
					&& !((String) obj.get("summary")).contains("Star")) // Removes common, unhelpful summaries
				positive += obj.get("summary");
			// Extract keywords if review is negative
			else if (negative.compareTo("") == 0 && (Double) obj.get("overall") < 3
					&& !((String) obj.get("summary")).contains("Star"))// Removes common, unhelpful summaries
				negative += obj.get("summary");

			// Break if keywords have been extracted
			if (positive.compareTo("") != 0 && negative.compareTo("") != 0)
				break;
		}
		
		// Creates return string, only populating if a keyword was extracted
		if (positive.compareTo("") != 0)
			positive = "What 4-5 Stars Say: " + positive;
		if (negative.compareTo("") != 0)
			negative = "   What 1-2 Stars Say: " + negative;
		return positive + negative;
	}

	/**
	 * Draws the swing home page for the search engine's GUI
	 */
	public void drawHome() {
		// Clear
		frame.getContentPane().removeAll();
		// Reset format
		frame.add(homePage, BorderLayout.CENTER);
		frame.pack();
	}

	/**
	 * Draws the swing results page for the search engine's GUI, populated with
	 * products retrieved using a user query and sorted based off the user's specification
	 * of by relevance or by rating
	 * 
	 * @param String query: The query provided by the user to be used for retrieval
	 * @param boolean sort: Whether the results should be sorted by relevance or ranking
	 * 						Sort by relevance if true, by ranking if false
	 * @throws Exception
	 */
	public void drawResults(String query, boolean sort) throws Exception {
		// create a search engine based on the params file.
		jsonConfigFile = "Data/rm_model.json";
		globalParams = Parameters.parseFile(jsonConfigFile);
		pathIndexBase = "Data/index";
		retrieval = RetrievalFactory.instance(pathIndexBase, Parameters.create());

		// Clears frame
		frame.getContentPane().removeAll();
		resultsText.removeAll();
		
		// Sets text
		resultsForLabel.setText("Results for " + '"' + query + '"');

		// Initializes and then populates results using user query
		List<String> results;
		if (sort)
			results = relevanceSearch(query.toLowerCase());
		else
			results = rankingSearch(query.toLowerCase());

		// Loops to draw results on GUI
		for (int i = 0; i < 30; i += 3) {
			// Breaks early if results has less than 10 products
			if (i == results.size())
				break;

			// Creates panel to contain product result
			JPanel resultsPane = new JPanel();
			resultsPane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 30));
			resultsPane.setLayout(new BorderLayout());

			// Creates panel to contain multiple lines of text for product result
			JPanel textPart = new JPanel();
			textPart.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 30));
			textPart.setLayout(new GridLayout(0, 1));
			textPart.add(new JLabel((i / 3 + 1) + ": " + results.get(i)));
			textPart.add(new JLabel(results.get(i + 2)));
			
			// Adds product text
			resultsPane.add(textPart, BorderLayout.LINE_START);
			
			// Adds image if it exists
			if (results.get(i + 1).compareTo("No Image") != 0) {
				URL url = new URL(results.get(i + 1));
				BufferedImage pic = ImageIO.read(url);
				resultsPane.add(new JLabel(new ImageIcon(pic)), BorderLayout.LINE_END);
			} else
				resultsPane.add(new JLabel(), BorderLayout.LINE_END);
			
			// Adds product to results pane
			resultsText.add(resultsPane);
		}

		// Shows results
		frame.add(resultsPage, BorderLayout.LINE_START);
		frame.pack();
	}

	/**
	 * Processes user interaction with GUI
	 */
	@Override
	public void actionPerformed(ActionEvent e) {
		// Handles case when user searches
		if (e.getActionCommand().compareTo("Search") == 0)
			try {
				// Runs user query and draws results sorted as specified by user
				drawResults((String) queryInput.getText(), relevanceOp.isSelected());
			} catch (ParseException | IOException e1) {
				e1.printStackTrace();
			} catch (Exception e1) {
				e1.printStackTrace();
			}
		// Handles when user wants to return to homepage
		else if (e.getActionCommand().compareTo("Back") == 0)
			drawHome();
	}

	/**
	 * Initializes GUI homepage
	 */
	private void initializeHome() {
		// Setup search button
		prompt = new JLabel("Please input a search");
		searchButton = new JButton("Search");
		searchButton.addActionListener(this);

		// Setup toggle for user to specify how to sort results
		inputToggle = new JPanel();
		searchOptions = new ButtonGroup();
		relevanceOp = new JToggleButton("Relevance");
		rankingOp = new JToggleButton("Rating");
		relevanceOp.setPreferredSize(new Dimension(80, 20));
		rankingOp.setPreferredSize(new Dimension(80, 20));
		relevanceOp.setSelected(true);
		searchOptions.add(relevanceOp);
		searchOptions.add(rankingOp);
		inputToggle.add(relevanceOp);
		inputToggle.add(rankingOp);

		// Sets up search box
		queryInput = new JTextField(50);

		// Lay everything out
		homePage = new JPanel();
		homePage.setBorder(BorderFactory.createEmptyBorder(30, 30, 10, 30));
		homePage.setLayout(new BorderLayout());
		homePage.add(prompt, BorderLayout.NORTH);
		homePage.add(queryInput, BorderLayout.LINE_START);
		homePage.add(inputToggle, BorderLayout.EAST);
		homePage.add(searchButton, BorderLayout.PAGE_END);
	}

	private void initializeResults() {
		resultsText = new JPanel();
		resultsText.setBorder(BorderFactory.createEmptyBorder(30, 30, 10, 30));
		resultsText.setLayout(new BoxLayout(resultsText, BoxLayout.PAGE_AXIS));

		backButton = new JButton("Back");
		backButton.addActionListener(this);

		resultsForLabel = new JLabel("");

		resultsPage = new JPanel();
		resultsPage.setBorder(BorderFactory.createEmptyBorder(30, 30, 10, 30));
		resultsPage.setLayout(new BorderLayout());
		resultsPage.add(resultsText, BorderLayout.CENTER);
		resultsPage.add(backButton, BorderLayout.PAGE_END);
		resultsPage.add(resultsForLabel, BorderLayout.PAGE_START);

	}


	/**
	 * Utilizes galago to run retrieval over amazon user dataset using a user specified query
	 * @param p: parameters used for galago retrieval;
	 * @param qid
	 * @param query
	 * @param retrieval
	 * @param model
	 * @param smoothing
	 * @param pathIndexBase
	 * @param requested
	 * @return
	 * @throws Exception
	 */
	private static LinkedHashMap<String, Double> runQuery(Parameters p, String qid, String query, Retrieval retrieval,
			String model, String smoothing, String pathIndexBase, int requested) throws Exception {
		p.set("requested", requested); // set the maximum number of document retrieved for each query.
		p.set("scorer", "dirichlet"); // set JM smoothing method
		p.set("mu", 2000); // set the parameters in JM method.

		if (model.length() > 0) {
			if (smoothing.length() > 0) {
				String[] terms = query.split(" ");
				query = "";
				for (String t : terms) {
					query += "#extents:part=" + model + ":" + t + "() ";
				}
			}
			query = "#combine" + "(" + query + ")"; // apply the retrieval model to the query if exists
		}
		Node root = StructuredQuery.parse(query); // turn the query string into a query tree
		System.out.println(root.toString());
		Node transformed = retrieval.transformQuery(root, p); // apply traversals
		System.out.println(transformed.toString());
		List<ScoredDocument> results = retrieval.executeQuery(transformed, p).scoredDocuments; // issue the query!
		System.out.println("****************");

		LinkedHashMap<String, Double> docs = new LinkedHashMap<String, Double>();
		for (ScoredDocument sd : results) {
			System.out.println(sd.getName() + ":" + sd.getScore());
			docs.put(sd.getName(), sd.getScore());
		}
		return docs;

	}

	/**
	 * Reads in Amazon product data from files and stores them in
	 * a number of data structures used for retrieval
	 * @throws ParseException
	 * @throws IOException
	 */
	private void loadData() throws ParseException, IOException {
		// Initializes fields used to store in read in data
		reviewMap = new HashMap<String, ArrayList<JSONObject>>();
		reviewAvg = new HashMap<String, Double>();
		metaMap = new HashMap<String, JSONObject>();

		// JSON Object used to iterate
		JSONObject obj;
		// The name of the file to open.
		String fileName = "Data/Musical_Instruments_5.json";

		// This will reference one line at a time
		String line = null;

		// Reads in product reviews
		try {
			// FileReader reads text files in the default encoding.
			FileReader fileReader = new FileReader(fileName);

			// Always wrap FileReader in BufferedReader.
			BufferedReader bufferedReader = new BufferedReader(fileReader);

			// Read every amazon product reviews in input file
			while ((line = bufferedReader.readLine()) != null) {
				obj = (JSONObject) new JSONParser().parse(line);

				// Adds new product if it doesn't appear yet
				if (!reviewMap.containsKey((String) obj.get("asin"))) {
					reviewMap.put((String) obj.get("asin"), new ArrayList<JSONObject>());
					reviewAvg.put((String) obj.get("asin"), 0.0);
				}

				// Adds review
				reviewMap.get((String) obj.get("asin")).add(obj);
				reviewAvg.put((String) obj.get("asin"),
						reviewAvg.get((String) obj.get("asin")) + (double) obj.get("overall"));
			}
			// Always close files.
			bufferedReader.close();
		} catch (FileNotFoundException ex) {
			System.out.println("Unable to open file '" + fileName + "'");
		} catch (IOException ex) {
			System.out.println("Error reading file '" + fileName + "'");
			// Or we could just do this:
			// ex.printStackTrace();
		} catch (ParseException e) {
			e.printStackTrace();
		}

		// Maps asin numbers to their average review
		for (String key : reviewMap.keySet())
			reviewAvg.put(key, reviewAvg.get(key) / reviewMap.get(key).size());

		// FileReader reads text files in the default encoding.
		FileReader fileReader = new FileReader("Data/meta_Musical_Instruments.json");

		// Always wrap FileReader in BufferedReader.
		BufferedReader bufferedReader = new BufferedReader(fileReader);

		// Reads in product metadata
		while ((line = bufferedReader.readLine()) != null) {
			obj = (JSONObject) new JSONParser().parse(line);

			if (reviewMap.containsKey((String) obj.get("asin"))) {
				metaMap.put((String) obj.get("asin"), obj);
			}
		}
		// Always close files.
		bufferedReader.close();

	}
	
	/**
	 * Program entry point
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
		new Search();
	}

}
