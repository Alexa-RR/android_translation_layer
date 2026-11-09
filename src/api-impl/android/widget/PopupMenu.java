/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.widget;
import android.view.KeyEvent;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.View.OnTouchListener;
import com.android.internal.R;
import java.util.ArrayList;
import java.util.List;

/**
 * A PopupMenu displays a {@link Menu} in a modal popup window anchored to a
 * {@link View}. The popup will appear below the anchor view if there is room,
 * or above it if there is not. If the IME is visible the popup will not
 * overlap it until it is touched. Touching outside of the popup will dismiss
 * it.
 */

public class PopupMenu {
	private final Context mContext;
	private final View mAnchor;
	private OnMenuItemClickListener mMenuItemClickListener;
	private OnDismissListener mOnDismissListener;
	private OnTouchListener mDragListener;
	private long popover;
	private MenuImpl menu;

	/**
	 * Constructor to create a new popup menu with an anchor view.
	 *
	 * @param context Context the popup menu is running in, through which it
	 *        can access the current theme, resources, etc.
	 * @param anchor Anchor view for this popup. The popup will appear below
	 *        the anchor if there is room, or above it if there is not.
	 */
	public PopupMenu(Context context, View anchor) {
		this(context, anchor, Gravity.NO_GRAVITY);
	}
	/**
	 * Constructor to create a new popup menu with an anchor view and alignment
	 * gravity.
	 *
	 * @param context Context the popup menu is running in, through which it
	 *        can access the current theme, resources, etc.
	 * @param anchor Anchor view for this popup. The popup will appear below
	 *        the anchor if there is room, or above it if there is not.
	 * @param gravity The {@link Gravity} value for aligning the popup with its
	 *        anchor.
	 */
	public PopupMenu(Context context, View anchor, int gravity) {
		this(context, anchor, gravity, R.attr.popupMenuStyle, 0);
	}
	/**
	 * Constructor a create a new popup menu with a specific style.
	 *
	 * @param context Context the popup menu is running in, through which it
	 *        can access the current theme, resources, etc.
	 * @param anchor Anchor view for this popup. The popup will appear below
	 *        the anchor if there is room, or above it if there is not.
	 * @param gravity The {@link Gravity} value for aligning the popup with its
	 *        anchor.
	 * @param popupStyleAttr An attribute in the current theme that contains a
	 *        reference to a style resource that supplies default values for
	 *        the popup window. Can be 0 to not look for defaults.
	 * @param popupStyleRes A resource identifier of a style resource that
	 *        supplies default values for the popup window, used only if
	 *        popupStyleAttr is 0 or can not be found in the theme. Can be 0
	 *        to not look for defaults.
	 */
	public PopupMenu(Context context, View anchor, int gravity, int popupStyleAttr,
	                 int popupStyleRes) {
		mContext = context;
		mAnchor = anchor;
		menu = new MenuImpl();
		popover = native_buildPopover(menu.menu);
	}

	/**
	 * @return a {@link MenuInflater} that can be used to inflate menu items
	 *         from XML into the menu returned by {@link #getMenu()}
	 * @see #getMenu()
	 */
	public MenuInflater getMenuInflater() {
		return new MenuInflater(mContext);
	}

	/**
	 * Sets a listener that will be notified when the user selects an item from
	 * the menu.
	 *
	 * @param listener the listener to notify
	 */
	public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {
		mMenuItemClickListener = listener;
	}

	/**
	 * Sets a listener that will be notified when this menu is dismissed.
	 *
	 * @param listener the listener to notify
	 */
	public void setOnDismissListener(OnDismissListener listener) {
		mOnDismissListener = listener;
	}

	protected native long native_init();
	protected native void native_insertItem(long menu, int position, String item, int id);
	protected native void native_insertSubmenu(long menu, int position, String item, long submenu);
	protected native void native_removeItem(long menu, int position);
	protected native long native_buildPopover(long menu);
	protected native void native_show(long popover, long anchor);

	// callback from native code
	protected void menuItemClickCallback(final int id) {
		// (layout) AOSP MenuItemImpl.invoke: the item's listener, the popup's, the intent, the provider
		final MenuItem item = getMenu().findItem(id);
		if (item instanceof MenuItemImpl)
			((MenuItemImpl) item).tlInvoke();
	}

	/**
	 * Inflate a menu resource into this PopupMenu. This is equivalent to
	 * calling {@code popupMenu.getMenuInflater().inflate(menuRes, popupMenu.getMenu())}.
	 *
	 * @param menuRes Menu resource to inflate
	 * @throws Exception
	 */
	public void inflate(int menuRes) throws Exception {
		getMenuInflater().inflate(menuRes, getMenu());
	}

	/**
	* Show the menu popup anchored to the view specified during construction.
	*
	* @see #dismiss()
	*/
	public void show() {
		System.out.println("PopupMenu.show() called");
		native_show(popover, mAnchor.widget);
	}

	public Menu getMenu() {
		return menu;
	}

	public void dismiss() {
		System.out.println("PopupMenu.dismiss() called");
	}

	/**
	 * Interface responsible for receiving menu item click events if the items
	 * themselves do not have individual item click listeners.
	 */
	public interface OnMenuItemClickListener {
		/**
		 * This method will be invoked when a menu item is clicked if the item
		 * itself did not already handle the event.
		 *
		 * @param item the menu item that was clicked
		 * @return {@code true} if the event was handled, {@code false}
		 *         otherwise
		 */
		boolean onMenuItemClick(MenuItem item);
	}

	/**
	 * Callback interface used to notify the application that the menu has closed.
	 */
	public interface OnDismissListener {
		/**
		 * Called when the associated menu has been dismissed.
		 *
		 * @param menu the popup menu that was dismissed
		 */
		void onDismiss(PopupMenu menu);
	}

	private class MenuImpl implements Menu {
		long menu = native_init();

		List<MenuItemImpl> items = new ArrayList<>();
		int numVisibleItems = 0;

		@Override
		public MenuItem add(int groupId, int itemId, int order, CharSequence title) {
			MenuItemImpl item = new MenuItemImpl(itemId, this, String.valueOf(title), null);
			item.tlGroupId = groupId; // (layout) AOSP MenuItemImpl keeps its group and order
			item.tlOrder = order;
			items.add(item);
			item.setVisible(true);
			return item;
		}

		@Override
		public MenuItem add(int groupId, int itemId, int order, int titleRes) {
			return add(groupId, itemId, order, mContext.getResources().getText(titleRes));
		}

		@Override
		public MenuItem findItem(int id) {
			for (MenuItemImpl item : items) {
				if (item.id == id)
					return item;
				if (item.subMenu != null) {
					MenuItem found = item.subMenu.findItem(id);
					if (found != null)
						return found;
				}
			}
			return null;
		}

		@Override
		public MenuItem getItem(int index) {
			return items.get(index);
		}

		@Override
		public void clear() {
			// (layout) AOSP MenuBuilder.clear: every item leaves the menu
			for (int i = items.size() - 1; i >= 0; i--)
				items.get(i).setVisible(false);
			items.clear();
		}

		@Override
		public void removeGroup(int groupId) {
			for (int i = items.size() - 1; i >= 0; i--) {
				if (items.get(i).tlGroupId == groupId) {
					items.get(i).setVisible(false);
					items.remove(i);
				}
			}
		}

		@Override
		public SubMenu addSubMenu(int titleRes) {
			return addSubMenu(NONE, NONE, NONE, mContext.getResources().getText(titleRes));
		}

		@Override
		public MenuItem add(int titleRes) {
			return add(NONE, NONE, NONE, mContext.getResources().getText(titleRes));
		}

		@Override
		public MenuItem add(CharSequence text) {
			return add(NONE, NONE, NONE, text);
		}

		@Override
		public void setGroupCheckable(int group, boolean checkable, boolean exclusive) {
			for (MenuItemImpl item : items) {
				if (item.tlGroupId == group)
					item.setCheckable(checkable);
			}
		}

		@Override
		public SubMenu addSubMenu(int groupId, int itemId, int order, CharSequence title) {
			SubMenuImpl submenu = new SubMenuImpl(itemId, this, String.valueOf(title));
			items.add(submenu.item);
			submenu.item.setVisible(true);
			return submenu;
		}

		@Override
		public void setGroupVisible(int group, boolean visible) {
			for (MenuItemImpl item : items) {
				if (item.tlGroupId == group)
					item.setVisible(visible);
			}
		}

		@Override
		public void removeItem(int id) {
			for (int i = 0; i < items.size(); i++) {
				if (items.get(i).id == id) {
					items.get(i).setVisible(false);
					items.remove(i);
					return;
				}
			}
		}

		@Override
		public int size() {
			return items.size();
		}

		@Override
		public boolean hasVisibleItems() {
			for (MenuItemImpl item : items) {
				if (item.isVisible())
					return true;
			}
			return false;
		}

		@Override
		public SubMenu addSubMenu(int groupId, int itemId, int order, int titleRes) {
			return addSubMenu(groupId, itemId, order, mContext.getResources().getText(titleRes));
		}

		@Override
		public int addIntentOptions(int groupId, int itemId, int order, ComponentName caller, Intent[] specifics, Intent intent, int flags, MenuItem[] outSpecificItems) {
			return 0;
		}
	
		/* teslalinux (layout): the AOSP Menu members ATL's menu lacked (MenuBuilder semantics) */
		private boolean tlQwertyMode;

		/** AOSP MenuBuilder.addSubMenu(CharSequence): a submenu at the end, no group or id */
		@Override
		public SubMenu addSubMenu(CharSequence title) {
			return addSubMenu(NONE, NONE, NONE, title);
		}

		/** AOSP: whether the items of the group can be chosen; the GTK menu shows every item enabled */
		@Override
		public void setGroupEnabled(int group, boolean enabled) {
			for (MenuItemImpl item : items) {
				if (item.tlGroupId == group)
					item.setEnabled(enabled);
			}
		}

		/** AOSP MenuBuilder.close: the menu's popup goes away */
		@Override
		public void close() {
			dismiss();
		}

		/** AOSP MenuBuilder.findItemWithShortcutForKey: the visible item whose shortcut is this key */
		MenuItemImpl tlItemForShortcut(int keyCode, KeyEvent event) {
			final boolean qwerty = tlQwertyMode;
			final char wanted = qwerty ? Character.toLowerCase((char) event.getUnicodeChar()) : (char) event.getNumber();
			for (MenuItemImpl item : items) {
				if (!item.isVisible())
					continue;
				final char shortcut = qwerty ? Character.toLowerCase(item.getAlphabeticShortcut()) : item.getNumericShortcut();
				if (shortcut != 0 && shortcut == wanted)
					return item;
				if (item.subMenu != null) {
					MenuItemImpl found = item.subMenu.tlItemForShortcut(keyCode, event);
					if (found != null)
						return found;
				}
			}
			return null;
		}

		/** AOSP: whether a key is the shortcut of one of the items */
		@Override
		public boolean isShortcutKey(int keyCode, KeyEvent event) {
			return tlItemForShortcut(keyCode, event) != null;
		}

		/** AOSP: the item whose shortcut the key is gets performed (FLAG_PERFORM_NO_CLOSE keeps the menu open) */
		@Override
		public boolean performShortcut(int keyCode, KeyEvent event, int flags) {
			final MenuItemImpl item = tlItemForShortcut(keyCode, event);
			boolean handled = false;
			if (item != null)
				handled = item.tlInvoke();
			if ((flags & FLAG_ALWAYS_PERFORM_CLOSE) != 0)
				close();
			return handled;
		}

		/** AOSP: the item with this id gets performed */
		@Override
		public boolean performIdentifierAction(int id, int flags) {
			final MenuItem item = findItem(id);
			final boolean handled = item instanceof MenuItemImpl && ((MenuItemImpl) item).tlInvoke();
			if ((flags & FLAG_PERFORM_NO_CLOSE) == 0)
				close();
			return handled;
		}

		/** AOSP: whether shortcuts are the alphabetic (qwerty keyboard) or the numeric ones */
		@Override
		public void setQwertyMode(boolean isQwerty) {
			tlQwertyMode = isQwerty;
		}
}

	private class SubMenuImpl extends MenuImpl implements SubMenu {
		private MenuItemImpl item;

		public SubMenuImpl(int id, MenuImpl parent, String title) {
			item = new MenuItemImpl(id, parent, title, this);
		}

		@Override
		public MenuItem getItem() {
			return item;
		}

		@Override
		public void clearHeader() {
			// TODO Auto-generated method stub
			throw new UnsupportedOperationException("Unimplemented method 'clearHeader'");
		}

		@Override
		public SubMenu setIcon(Drawable icon) {
			// TODO Auto-generated method stub
			throw new UnsupportedOperationException("Unimplemented method 'setIcon'");
		}
	}

	private class MenuItemImpl implements MenuItem {
		private int id;
		private MenuImpl parent;
		private String title;
		SubMenuImpl subMenu;
		int position; // position in list of visible items, or -1 if not visible

		private MenuItemImpl(int id, MenuImpl parent, String title, SubMenuImpl subMenu) {
			this.id = id;
			this.parent = parent;
			this.position = -1;
			this.title = title;
			this.subMenu = subMenu;
		}

		@Override
		public MenuItem setIcon(int iconRes) {
			return setIcon(iconRes != 0 ? mContext.getDrawable(iconRes) : null);
		}

		@Override
		public MenuItem setIcon(Drawable icon) {
			tlIcon = icon; // (layout) kept for getIcon; the GTK menu shows text items
			return this;
		}
		private Drawable tlIcon;

		@Override
		public MenuItem setVisible(boolean visible) {
			// GMenu doesn't support invisible items, so we remove them while they're not visible
			if (!visible && isVisible()) {
				parent.numVisibleItems--;
				for (int i = parent.items.size() - 1; i >= 0; i--) {
					MenuItemImpl item = parent.items.get(i);
					if (item != this && item.isVisible())
						item.position--;
					else if (item == this)
						break;
				}
				native_removeItem(parent.menu, position);
				position = -1;
			} else if (visible && !isVisible()) {
				position = parent.numVisibleItems++;
				for (int i = parent.items.size() - 1; i >= 0; i--) {
					MenuItemImpl item = parent.items.get(i);
					if (item != this && item.isVisible())
						position = item.position++;
					else if (item == this)
						break;
				}
				if (subMenu != null)
					native_insertSubmenu(parent.menu, position, title, subMenu.menu);
				else
					native_insertItem(parent.menu, position, title, id);
			}
			return this;
		}

		@Override
		public MenuItem setChecked(boolean checked) {
			tlChecked = checked;
			return this;
		}

		@Override
		public MenuItem setEnabled(boolean enabled) {
			tlEnabled = enabled;
			return this;
		}

		@Override
		public MenuItem setCheckable(boolean checkable) {
			tlCheckable = checkable;
			return this;
		}

		@Override
		public boolean isCheckable() {
			return tlCheckable;
		}

		@Override
		public MenuItem setTitleCondensed(CharSequence titleCondensed) {
			tlTitleCondensed = titleCondensed;
			return this;
		}

		@Override
		public MenuItem setTitle(CharSequence title) {
			this.title = String.valueOf(title);
			return this;
		}

		@Override
		public MenuItem setActionView(View actionView) {
			tlActionView = actionView;
			tlActionProvider = null;
			return this;
		}

		@Override
		public void setShowAsAction(int action) {
			tlShowAsAction = action;
		}

		@Override
		public int getItemId() {
			return id;
		}

		@Override
		public int getGroupId() {
			return tlGroupId;
		}

		@Override
		public MenuItem setOnMenuItemClickListener(OnMenuItemClickListener listener) {
			tlClickListener = listener;
			return this;
		}

		@Override
		public MenuItem setTitle(int resId) {
			return setTitle(mContext.getResources().getText(resId));
		}

		@Override
		public boolean isVisible() {
			return position >= 0;
		}

		@Override
		public Drawable getIcon() {
			return tlIcon; // AOSP: null without an icon
		}

		@Override
		public SubMenu getSubMenu() {
			return subMenu;
		}

		@Override
		public MenuItem setActionView(int resId) {
			return setActionView(android.view.LayoutInflater.from(mContext).inflate(resId, null, false));
		}

		@Override
		public View getActionView() {
			if (tlActionView != null)
				return tlActionView;
			if (tlActionProvider != null) {
				tlActionView = tlActionProvider.onCreateActionView(this);
				return tlActionView;
			}
			return null;
		}

		@Override
		public boolean hasSubMenu() {
			return subMenu != null;
		}

		@Override
		public MenuItem setOnActionExpandListener(OnActionExpandListener listener) {
			tlOnActionExpandListener = listener;
			return this;
		}

		@Override
		public boolean isChecked() {
			return tlChecked;
		}

		@Override
		public MenuItem setShowAsActionFlags(int action) {
			setShowAsAction(action);
			return this;
		}

		@Override
		public MenuItem setAlphabeticShortcut(char alphaChar) {
			tlShortcutAlphabeticChar = Character.toLowerCase(alphaChar);
			return this;
		}

		@Override
		public MenuItem setShortcut(char numeric, char alpha) {
			tlShortcutNumericChar = numeric;
			tlShortcutAlphabeticChar = Character.toLowerCase(alpha);
			return this;
		}

		@Override
		public int getOrder() {
			return tlOrder;
		}

		@Override
		public boolean isEnabled() {
			return tlEnabled;
		}

		@Override
		public CharSequence getTitleCondensed() {
			return tlTitleCondensed != null ? tlTitleCondensed : title;
		}

		@Override
		public CharSequence getTitle() {
			return title;
		}

		@Override
		public MenuItem setNumericShortcut(char numericChar) {
			tlShortcutNumericChar = numericChar;
			return this;
		}

		@Override
		public boolean expandActionView() {
			// AOSP MenuItemImpl: only a collapsible action view expands, if the listener lets it
			if ((tlShowAsAction & SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW) == 0 || getActionView() == null)
				return false;
			if (tlOnActionExpandListener == null || tlOnActionExpandListener.onMenuItemActionExpand(this)) {
				tlActionViewExpanded = true;
				return true;
			}
			return false;
		}

		@Override
		public boolean isActionViewExpanded() {
			return tlActionViewExpanded;
		}

		@Override
		public MenuItem setIntent(Intent intent) {
			tlIntent = intent;
			return this;
		}
	
		/* teslalinux (layout): AOSP MenuItemImpl state ATL's item lacked */
		int tlGroupId;
		int tlOrder;
		private Intent tlIntent;
		private char tlShortcutNumericChar;
		private int tlShortcutNumericModifiers = KeyEvent.META_CTRL_ON;
		private char tlShortcutAlphabeticChar;
		private int tlShortcutAlphabeticModifiers = KeyEvent.META_CTRL_ON;
		private android.view.ActionProvider tlActionProvider;
		private View tlActionView;
		private boolean tlActionViewExpanded;
		private OnActionExpandListener tlOnActionExpandListener;
		private OnMenuItemClickListener tlClickListener;
		private boolean tlCheckable;
		private boolean tlChecked;
		private boolean tlEnabled = true;
		private CharSequence tlTitleCondensed;
		private int tlShowAsAction;
		private CharSequence tlContentDescription;
		private CharSequence tlTooltipText;

		/**
		 * AOSP MenuItemImpl.invoke: the item's own listener, then the popup's listener, then the
		 * intent (started as an activity), then the action provider's default action.
		 */
		boolean tlInvoke() {
			if (tlClickListener != null && tlClickListener.onMenuItemClick(this))
				return true;
			if (mMenuItemClickListener != null && mMenuItemClickListener.onMenuItemClick(this))
				return true;
			if (tlIntent != null) {
				try {
					mContext.startActivity(tlIntent);
					return true;
				} catch (android.content.ActivityNotFoundException e) {
					android.util.Log.e("PopupMenu", "Can't find activity to handle intent; ignoring", e);
				}
			}
			return tlActionProvider != null && tlActionProvider.onPerformDefaultAction();
		}

		@Override
		public Intent getIntent() {
			return tlIntent;
		}

		@Override
		public char getNumericShortcut() {
			return tlShortcutNumericChar;
		}

		@Override
		public char getAlphabeticShortcut() {
			return tlShortcutAlphabeticChar;
		}

		@Override
		public MenuItem setNumericShortcut(char numericChar, int numericModifiers) {
			tlShortcutNumericChar = numericChar;
			tlShortcutNumericModifiers = KeyEvent.normalizeMetaState(numericModifiers);
			return this;
		}

		@Override
		public int getNumericModifiers() {
			return tlShortcutNumericModifiers;
		}

		@Override
		public MenuItem setAlphabeticShortcut(char alphaChar, int alphaModifiers) {
			tlShortcutAlphabeticChar = Character.toLowerCase(alphaChar);
			tlShortcutAlphabeticModifiers = KeyEvent.normalizeMetaState(alphaModifiers);
			return this;
		}

		@Override
		public int getAlphabeticModifiers() {
			return tlShortcutAlphabeticModifiers;
		}

		@Override
		public MenuItem setShortcut(char numericChar, char alphaChar, int numericModifiers, int alphaModifiers) {
			setNumericShortcut(numericChar, numericModifiers);
			return setAlphabeticShortcut(alphaChar, alphaModifiers);
		}

		/** AOSP: an item of a context menu carries the menu info; a popup menu's items have none */
		@Override
		public android.view.ContextMenu.ContextMenuInfo getMenuInfo() {
			return null;
		}

		@Override
		public MenuItem setActionProvider(android.view.ActionProvider actionProvider) {
			if (tlActionProvider != null)
				tlActionProvider.reset();
			tlActionView = null;
			tlActionProvider = actionProvider;
			return this;
		}

		@Override
		public android.view.ActionProvider getActionProvider() {
			return tlActionProvider;
		}

		/** AOSP: an expanded action view collapses, if the listener lets it */
		@Override
		public boolean collapseActionView() {
			if ((tlShowAsAction & SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW) == 0 || tlActionView == null)
				return false;
			if (!tlActionViewExpanded)
				return false;
			if (tlOnActionExpandListener == null || tlOnActionExpandListener.onMenuItemActionCollapse(this)) {
				tlActionViewExpanded = false;
				return true;
			}
			return false;
		}

		@Override
		public MenuItem setContentDescription(CharSequence contentDescription) {
			tlContentDescription = contentDescription;
			return this;
		}

		@Override
		public CharSequence getContentDescription() {
			return tlContentDescription;
		}

		@Override
		public MenuItem setTooltipText(CharSequence tooltipText) {
			tlTooltipText = tooltipText;
			return this;
		}

		@Override
		public CharSequence getTooltipText() {
			return tlTooltipText;
		}
}
}
