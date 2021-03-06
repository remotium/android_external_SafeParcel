package org.microg.safeparcel;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SafeParcelUtil {
    private static final String TAG = "SafeParcel";

    public static <T extends SafeParcelable> T createObject(Class<T> tClass, Parcel in) {
        try {
            Constructor<T> constructor = tClass.getDeclaredConstructor();
            boolean acc = constructor.isAccessible();
            constructor.setAccessible(true);
            T t = constructor.newInstance();
            readObject(t, in);
            constructor.setAccessible(acc);
            return t;
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("createObject() requires a default constructor");
        } catch (Exception e) {
            throw new RuntimeException("Can't construct object", e);
        }
    }

    public static void writeObject(SafeParcelable object, Parcel parcel, int flags) {
        if (object == null)
            throw new NullPointerException();
        Class clazz = object.getClass();
        int start = SafeParcelWriter.writeStart(parcel);
        while (clazz != null) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(SafeParceled.class)) {
                    try {
                        writeField(object, parcel, field, flags);
                    } catch (Exception e) {
                        Log.w(TAG, "Error writing field: " + e);
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        SafeParcelWriter.writeEnd(parcel, start);
    }

    public static void readObject(SafeParcelable object, Parcel parcel) {
        if (object == null)
            throw new NullPointerException();
        Class clazz = object.getClass();
        Map<Integer, Field> fieldMap = new HashMap<Integer, Field>();
        while (clazz != null) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(SafeParceled.class)) {
                    int fieldNum = field.getAnnotation(SafeParceled.class).value();
                    if (fieldMap.containsKey(fieldNum)) {
                        throw new RuntimeException(
                                "Field number " + fieldNum + " is used twice in " +
                                        clazz.getName() + " for fields " + field.getName() +
                                        " and " + fieldMap.get(fieldNum).getName());
                    }
                    fieldMap.put(fieldNum, field);
                }
            }
            clazz = clazz.getSuperclass();
        }
        clazz = object.getClass();
        int end = SafeParcelReader.readStart(parcel);
        while (parcel.dataPosition() < end) {
            int position = SafeParcelReader.readSingleInt(parcel);
            int fieldNum = SafeParcelReader.halfOf(position);
            if (!fieldMap.containsKey(fieldNum)) {
                Log.w(TAG,
                        "Unknown field num " + fieldNum + " in " + clazz.getName() + ", skipping.");
                SafeParcelReader.skip(parcel, position);
            } else {
                try {
                    readField(object, parcel, fieldMap.get(fieldNum), position);
                } catch (Exception e) {
                    Log.w(TAG, "Error reading field: " + fieldNum + " in " + clazz.getName() +
                            ", skipping.", e);
                    SafeParcelReader.skip(parcel, position);
                }
            }
        }
        if (parcel.dataPosition() > end) {
            throw new RuntimeException("Overread allowed size end=" + end);
        }
    }

    private static Parcelable.Creator getCreator(Field field) throws IllegalAccessException {
        Class clazz = field.getType();
        if (clazz.isArray()) {
            clazz = clazz.getComponentType();
        }
        if (Parcelable.class.isAssignableFrom(clazz)) {
            try {
                return (Parcelable.Creator) clazz.getDeclaredField("CREATOR").get(null);
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(clazz + " is an Parcelable without CREATOR");
            }
        }
        throw new RuntimeException(clazz + " is not an Parcelable");
    }

    private static ClassLoader getClassLoader(Field field) {
        try {
            String type = field.getAnnotation(SafeParceled.class).subType();
            return Class.forName(type).getClassLoader();
        } catch (ClassNotFoundException e) {
            return ClassLoader.getSystemClassLoader();
        }
    }

    private static ClassLoader getArrayClassLoader(Field field) {
        return field.getType().getComponentType().getClassLoader();
    }

    private static void writeField(SafeParcelable object, Parcel parcel, Field field, int flags)
            throws IllegalAccessException {
        int num = field.getAnnotation(SafeParceled.class).value();
        boolean mayNull = field.getAnnotation(SafeParceled.class).mayNull();
        boolean acc = field.isAccessible();
        field.setAccessible(true);
        switch (SafeParcelType.fromClass(field.getType())) {
            case Parcelable:
                SafeParcelWriter.write(parcel, num, (Parcelable) field.get(object), flags, mayNull);
                break;
            case Binder:
                SafeParcelWriter.write(parcel, num, (IBinder) field.get(object), mayNull);
                break;
            case List:
                SafeParcelWriter.write(parcel, num, (List) field.get(object), mayNull);
                break;
            case ParcelableArray:
                SafeParcelWriter.write(parcel, num, (Parcelable[]) field.get(object), flags, mayNull);
                break;
            case StringArray:
                SafeParcelWriter.write(parcel, num, (String[]) field.get(object), mayNull);
                break;
            case ByteArray:
                SafeParcelWriter.write(parcel, num, (byte[]) field.get(object), mayNull);
                break;
            case Integer:
                SafeParcelWriter.write(parcel, num, (Integer) field.get(object));
                break;
            case Long:
                SafeParcelWriter.write(parcel, num, (Long) field.get(object));
                break;
            case Boolean:
                SafeParcelWriter.write(parcel, num, (Boolean) field.get(object));
                break;
            case Float:
                SafeParcelWriter.write(parcel, num, (Float) field.get(object));
                break;
            case Double:
                SafeParcelWriter.write(parcel, num, (Double) field.get(object));
                break;
            case String:
                SafeParcelWriter.write(parcel, num, (String) field.get(object), mayNull);
        }
        field.setAccessible(acc);
    }

    private static void readField(SafeParcelable object, Parcel parcel, Field field, int position)
            throws IllegalAccessException {
        boolean acc = field.isAccessible();
        field.setAccessible(true);
        switch (SafeParcelType.fromClass(field.getType())) {
            case Parcelable:
                field.set(object, SafeParcelReader.readParcelable(parcel, position, getCreator(field)));
                break;
            case Binder:
                field.set(object, SafeParcelReader.readBinder(parcel, position));
                break;
            case List:
                field.set(object, SafeParcelReader.readList(parcel, position, getClassLoader(field)));
                break;
            case ParcelableArray:
                field.set(object, SafeParcelReader.readParcelableArray(parcel, position, getCreator(field)));
                break;
            case StringArray:
                field.set(object, SafeParcelReader.readStringArray(parcel, position));
                break;
            case ByteArray:
                field.set(object, SafeParcelReader.readByteArray(parcel, position));
                break;
            case Integer:
                field.set(object, SafeParcelReader.readInt(parcel, position));
                break;
            case Long:
                field.set(object, SafeParcelReader.readLong(parcel, position));
                break;
            case Boolean:
                field.set(object, SafeParcelReader.readBool(parcel, position));
                break;
            case Float:
                field.set(object, SafeParcelReader.readFloat(parcel, position));
                break;
            case Double:
                field.set(object, SafeParcelReader.readDouble(parcel, position));
                break;
            case String:
                field.set(object, SafeParcelReader.readString(parcel, position));
        }
        field.setAccessible(acc);
    }

    private enum SafeParcelType {
        Parcelable, Binder, List, ParcelableArray, StringArray, ByteArray,
        Integer, Long, Boolean, Float, Double, String;

        public static SafeParcelType fromClass(Class clazz) {
            if (clazz.isArray() && Parcelable.class.isAssignableFrom(clazz.getComponentType()))
                return ParcelableArray;
            if (clazz.isArray() && String.class.isAssignableFrom(clazz.getComponentType()))
                return StringArray;
            if (clazz.isArray() && byte.class.isAssignableFrom(clazz.getComponentType()))
                return ByteArray;
            if (Parcelable.class.isAssignableFrom(clazz))
                return Parcelable;
            if (IBinder.class.isAssignableFrom(clazz))
                return Binder;
            if (clazz == List.class || clazz == ArrayList.class)
                return List;
            if (clazz == int.class || clazz == Integer.class)
                return Integer;
            if (clazz == boolean.class || clazz == Boolean.class)
                return Boolean;
            if (clazz == long.class || clazz == Long.class)
                return Long;
            if (clazz == float.class || clazz == Float.class)
                return Float;
            if (clazz == double.class || clazz == Double.class)
                return Double;
            if (clazz == java.lang.String.class)
                return String;
            throw new RuntimeException("Type is not yet usable with SafeParcelUtil: " + clazz);
        }
    }
}
