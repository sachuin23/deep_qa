package org.allenai.semparse.parse

trait TreeTransformer {
  def transform(tree: DependencyTree): DependencyTree
}

abstract class BaseTransformer extends TreeTransformer {
  def transformChildren(tree: DependencyTree): DependencyTree = {
    DependencyTree(tree.token, tree.children.map(childWithLabel => {
      val label = childWithLabel._2
      val child = childWithLabel._1
      (transform(child), label)
    }))
  }
}

object transformers {

  /**
   * Replaces the _first_ occurrence of a child with label childLabel with newChild.  This is NOT
   * recursive, and will throw an error if the given child label is not found.
   *
   * TODO(matt): I may need to change token indices with this, too, as getting the yield of a
   * dependency tree sorts by token index.  So far I only call yield on NPs, which don't get
   * modified with these methods, so we should be ok.  But this could be an issue later.
   */
  def replaceChild(
    tree: DependencyTree,
    childLabel: String,
    newChild: DependencyTree,
    newLabel: String
  ): DependencyTree = {
    val childWithIndex = tree.children.zipWithIndex.find(_._1._2 == childLabel)
    childWithIndex match {
      case None => throw new IllegalStateException(s"Didn't find child with label $childLabel")
      case Some((child, indexToReplace)) => {
        val newChildren = tree.children.zipWithIndex.map(c => {
          val index = c._2
          if (index == indexToReplace) {
            (newChild, newLabel)
          } else {
            c._1
          }
        })
        DependencyTree(tree.token, newChildren)
      }
    }
  }

  /**
   * Traverses the tree to find matching subtrees, and replaces them with the given tree.
   */
  def replaceTree(
    tree: DependencyTree,
    treeToReplace: DependencyTree,
    replaceWith: DependencyTree
  ): DependencyTree = {
    if (tree == treeToReplace) {
      replaceWith
    } else {
      DependencyTree(tree.token, tree.children.map(childWithLabel => {
        val label = childWithLabel._2
        val child = childWithLabel._1
        (replaceTree(child, treeToReplace, replaceWith), label)
      }))
    }
  }

  /**
   * Removes the _first_ occurrence of a child with label childLabel.  ThiNOT recursive, and will
   * throw an error if the given child label is not found.
   */
  def removeChild(tree: DependencyTree, childLabel: String): DependencyTree = {
    val childWithIndex = tree.children.zipWithIndex.find(_._1._2 == childLabel)
    childWithIndex match {
      case None => throw new IllegalStateException(s"Didn't find child with label $childLabel")
      case Some((child, indexToRemove)) => {
        val newChildren = tree.children.zipWithIndex.flatMap(c => {
          val index = c._2
          if (index == indexToRemove) {
            Seq()
          } else {
            Seq(c._1)
          }
        })
        DependencyTree(tree.token, newChildren)
      }
    }
  }

  /**
   * Traverses the tree to find matching subtrees, and removes them.
   */
  def removeTree(tree: DependencyTree, treeToRemove: DependencyTree): DependencyTree = {
    DependencyTree(tree.token, tree.children.flatMap(childWithLabel => {
      val label = childWithLabel._2
      val child = childWithLabel._1
      if (child == treeToRemove) {
        Seq()
      } else {
        Seq((removeTree(child, treeToRemove), label))
      }
    }))
  }

  /**
   * Adds a child node with the given label to the end of the tree's children list.
   */
  def addChild(tree: DependencyTree, child: DependencyTree, label: String): DependencyTree = {
    DependencyTree(tree.token, tree.children ++ Seq((child, label)))
  }

  /**
   * Finds the subtree that corresponds to a wh-phrase, which would be replaced if we were turning
   * a question into a declarative sentence.  We currently assume that all wh-phrases are of the
   * form "(Which|What) NP? VP", where "which" or "what" is the (determiner) child of the NP, or
   * "Which of NP VP", where "which" is the (still determiner) head of the NP.
   */
  def findWhPhrase(tree: DependencyTree): Option[DependencyTree] = {
    if (isWhWord(tree.token)) {
      // We'll check this as a base case; we'll have already caught cases like "which gas", where
      // "gas" is the head, higher up in the tree.
      Some(tree)
    } else {
      val hasWhChild = tree.children.exists(c => isWhWord(c._1.token) && c._1.children.size == 0)
      if (hasWhChild) {
        Some(tree)
      } else {
        val childrenWhPhrases = tree.children.map(c => findWhPhrase(c._1))
        val successes = childrenWhPhrases.flatMap(_.toList)
        if (successes.size == 0) {
          None
        } else if (successes.size == 1) {
          Some(successes.head)
        } else {
          throw new IllegalStateException("found multiple wh-phrases - is this a real sentence?")
        }
      }
    }
  }

  def isWhWord(token: Token): Boolean = {
    token.lemma == "which" || token.lemma == "what"
  }

  object UndoPassivization extends BaseTransformer {
    override def transform(tree: DependencyTree): DependencyTree = {
      val children = tree.children
      if (children.size < 3) {
        transformChildren(tree)
      } else {
        val labels = children.map(_._2).toSet
        if (labels.contains("nsubjpass") && labels.contains("agent") && labels.contains("auxpass")) {
          val nsubjpass = children.find(_._2 == "nsubjpass").get._1
          val agent = children.find(_._2 == "agent").get._1
          // We take what was the agent, and make it the subject (putting the agent tree where
          // "nsubjpass" was).
          var transformed = replaceChild(tree, "nsubjpass", agent, "nsubj")
          // We take what was the object and make it the subject (putting the nsubjpass tree where
          // "agent" was).
          transformed = replaceChild(transformed, "agent", nsubjpass, "dobj")
          // And we remove the auxpass auxiliary (typically "is" or "was").
          transformed = removeChild(transformed, "auxpass")
          transformChildren(transformed)
        } else {
          transformChildren(tree)
        }
      }
    }
  }

  object RemoveDeterminers extends BaseTransformer {
    override def transform(tree: DependencyTree): DependencyTree = {
      DependencyTree(tree.token, tree.children.flatMap(childWithLabel => {
        val label = childWithLabel._2
        val child = childWithLabel._1
        if (label == "det" && child.isDeterminer && child.children.size == 0) {
          Seq()
        } else {
          Seq((transform(child), label))
        }
      }))
    }
  }

  object CombineParticles extends BaseTransformer {
    override def transform(tree: DependencyTree): DependencyTree = {
      tree.getChildWithLabel("prt") match {
        case None => transformChildren(tree)
        case Some(child) => {
          val newToken = tree.token.combineWith(child.token)
          val newTree = DependencyTree(newToken, tree.children.filterNot(_._2 == "prt"))
          transformChildren(newTree)
        }
      }
    }
  }

  /**
   * Removes a few words that are particular to our science questions.  A question will often say
   * something like, "which of the following is the best conductor of electricity?", with answer
   * options "iron rod", "plastic spoon", and so on.  What we really want to score is "An iron rod
   * is a conductor of electricity" vs "A plastic spoon is a conductor of electricity" - the
   * superlative "best" is captured implicitly in our ranking, and so we don't need it to be part
   * of the logical form that we score.  So we're going to remove a few specific words that capture
   * this notion.
   */
  object RemoveSuperlatives extends BaseTransformer {
    def isMatchingSuperlative(tree: DependencyTree): Boolean = {
      tree.token.lemma == "most" && tree.children.size == 0
    }

    override def transform(tree: DependencyTree): DependencyTree = {
      tree.children.find(c => isMatchingSuperlative(c._1) && c._2 == "amod") match {
        case None => transformChildren(tree)
        case Some((child, label)) => {
          transformChildren(removeTree(tree, child))
        }
      }
    }
  }

  class ReplaceWhPhrase(replaceWith: DependencyTree) extends BaseTransformer {
    override def transform(tree: DependencyTree) = {
      findWhPhrase(tree) match {
        case None => tree
        case Some(whTree) => replaceTree(tree, whTree, replaceWith)
      }
    }
  }

  object SplitConjunctions {
    def findConjunctions(tree: DependencyTree): Set[(DependencyTree, DependencyTree)] = {
      val children = tree.children.toSet
      children.flatMap(childWithLabel => {
        val child = childWithLabel._1
        val label = childWithLabel._2
        if (label == "conj_and") {
          Set((tree, child)) ++ findConjunctions(child)
        } else {
          findConjunctions(child)
        }
      })
    }

    def transform(tree: DependencyTree): Set[DependencyTree] = {
      // Basic outline here:
      // 1. find all conjunctions, paired with the parent node
      // 2. group by the parent node, in case there is more than one conjunction in the sentence
      // 3. for the first conjunction:
      //   4. remove all conjunction trees, forming a tree with just one of the conjoined nodes
      //   5. for each conjunction tree, remove the parent, and replace it with the conjunction tree
      //   6. for each of these constructed trees, recurse

      // Step 1
      val conjunctionTrees = findConjunctions(tree)

      // Step 2
      conjunctionTrees.groupBy(_._1).headOption match {
        case None => Set(tree)
        // Step 3
        case Some((parent, childrenWithParent)) => {
          val children = childrenWithParent.map(_._2)

          // Step 4
          var justFirstConjunction = tree
          var justParent = parent
          for (child <- children) {
            justFirstConjunction = removeTree(justFirstConjunction, child)
            justParent = removeTree(justParent, child)
          }

          // Step 5
          val otherConjunctions = children.map(child => {
            replaceTree(justFirstConjunction, justParent, child)
          })

          // Step 6
          val separatedTrees = Set(justFirstConjunction) ++ otherConjunctions
          separatedTrees.flatMap(transform)
        }
      }
    }
  }

  // This is very similar to SplitConjunctions, but the tree created by the Stanford parser is
  // slightly different, so there are a few minor changes here.
  // TODO(matt): actually, I only needed to change the findConjunctions method, and everything else
  // was the same.  I should make these two transformers share code.
  object SplitAppositives {
    def findAppositives(tree: DependencyTree): Set[(DependencyTree, DependencyTree)] = {
      val children = tree.children.toSet
      children.flatMap(childWithLabel => {
        val child = childWithLabel._1
        val label = childWithLabel._2
        if (label == "appos") {
          Set((tree, child)) ++ findAppositives(child)
        } else {
          findAppositives(child)
        }
      })
    }

    def transform(tree: DependencyTree): Set[DependencyTree] = {
      // Basic outline here:
      // 1. find all appositives, paired with the parent node
      // 2. group by the parent node, in case there is more than one appositives in the sentence
      // 3. for the first appositives:
      //   4. remove all appositives trees, forming a tree with just the head NP
      //   5. for each appositive tree, remove the parent, and replace it with the conjunction tree
      //   6. for each of these constructed trees, recurse

      // Step 1
      val apposTrees = findAppositives(tree)

      // Step 2
      apposTrees.groupBy(_._1).headOption match {
        case None => Set(tree)
        // Step 3
        case Some((parent, childrenWithParent)) => {
          val children = childrenWithParent.map(_._2)

          // Step 4
          var justFirstAppositive = tree
          var justParent = parent
          for (child <- children) {
            justFirstAppositive = removeTree(justFirstAppositive, child)
            justParent = removeTree(justParent, child)
          }

          // Step 5
          val otherAppositives = children.map(child => {
            replaceTree(justFirstAppositive, justParent, child)
          })

          // Step 6
          val separatedTrees = Set(justFirstAppositive) ++ otherAppositives
          separatedTrees.flatMap(transform)
        }
      }
    }
  }

  object RemoveBareCCs extends BaseTransformer {
    override def transform(tree: DependencyTree): DependencyTree = {
      tree.children.find(c => c._2 == "cc" && c._1.token.posTag == "CC" && c._1.children.size == 0) match {
        case None => transformChildren(tree)
        case Some((child, label)) => {
          transformChildren(removeTree(tree, child))
        }
      }
    }
  }

  object RemoveAuxiliaries extends BaseTransformer {
    override def transform(tree: DependencyTree): DependencyTree = {
      tree.children.find(c => c._2 == "aux" && c._1.token.posTag == "MD" && c._1.children.size == 0) match {
        case None => transformChildren(tree)
        case Some((child, label)) => {
          transformChildren(removeTree(tree, child))
        }
      }
    }
  }
}
